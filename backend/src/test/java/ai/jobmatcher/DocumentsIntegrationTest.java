package ai.jobmatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ai.jobmatcher.jobs.JobOfferRepository;
import ai.jobmatcher.jobs.OfferIngestService;
import ai.jobmatcher.jobs.RawJob;
import ai.jobmatcher.jobs.RemoteMode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Letters, application emails, exports and HR contact discovery, end to end on a real PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class DocumentsIntegrationTest {

    private static final HttpServer STUB = startStub();

    private static HttpServer startStub() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/api/job-board-api", ex -> reply(ex, "application/json", read("arbeitnow.json")));
            s.createContext("/jobwithcontact", ex -> reply(ex, "text/html", read("jobposting-contact.html")));
            s.createContext("/robots.txt", ex -> reply(ex, "text/plain", "User-agent: *\nDisallow:\n"));
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/fixtures", name), StandardCharsets.UTF_8);
    }

    private static void reply(HttpExchange ex, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private static String stub(String path) {
        return "http://127.0.0.1:" + STUB.getAddress().getPort() + path;
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.jobs.arbeitnow.endpoint", () -> stub("/api/job-board-api"));
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    private static final String JAVA_CV = """
            Jane Doe
            jane.doe@example.org

            Profile
            Backend engineer focused on Java services and PostgreSQL databases.

            Experience
            Senior Java Developer - Northwind Labs
            Jan 2019 - Present
            - Designed REST APIs with Spring Boot used by 12 internal teams
            - Reduced report time by 40% by tuning PostgreSQL queries

            Skills
            Java, Spring Boot, PostgreSQL, Docker

            Languages
            English (C1)
            """;

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JobOfferRepository offers;
    @Autowired OfferIngestService ingest;

    @BeforeEach
    void clean() {
        offers.deleteAll();
    }

    private MockHttpSession register() throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"docs-%s@example.org","password":"correct horse battery","displayName":"Jane Doe","consent":true}
                """.formatted(UUID.randomUUID()))).andExpect(status().isCreated()).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private void search(MockHttpSession s) throws Exception {
        mvc.perform(post("/api/searches/run").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
    }

    private String createCv(MockHttpSession s, String text) throws Exception {
        MvcResult r = mvc.perform(post("/api/cvs/text").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("label", "CV", "text", text)))).andExpect(status().isCreated()).andReturn();
        return body(r).path("summary").path("id").asString();
    }

    private String javaJobId(MockHttpSession s) throws Exception {
        return body(mvc.perform(get("/api/jobs").param("q", "java").session(s)).andReturn()).path("items").get(0).path("id").asString();
    }

    private static String request(String cvId, String extra) {
        return "{\"cvId\":\"" + cvId + "\"" + extra + "}";
    }

    private MvcResult postJson(MockHttpSession s, String url, String body) throws Exception {
        return mvc.perform(post(url).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    // ---- letters ----

    @Test
    void letterIsGroundedVersionedEditableAndExportable() throws Exception {
        MockHttpSession s = register();
        search(s);
        String cv = createCv(s, JAVA_CV);
        String job = javaJobId(s);

        MvcResult first = mvc.perform(post("/api/jobs/" + job + "/letters").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(request(cv, ",\"tone\":\"WARM\",\"length\":\"DETAILED\"")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.language").value("en"))
                .andExpect(jsonPath("$.source").value("GENERATED")).andExpect(jsonPath("$.generator").value("template"))
                .andExpect(jsonPath("$.note").value("ai-unusable")) // the mock model returns nothing usable, so the grounded template is used
                .andExpect(jsonPath("$.facts.length()").isNotEmpty()).andExpect(jsonPath("$.warnings.length()").value(0)).andReturn();
        JsonNode v1 = body(first);
        assertThat(v1.path("body").asString()).contains("Dear Sir or Madam,").contains("Designed REST APIs with Spring Boot").endsWith("Jane Doe");
        assertThat(v1.path("paragraphFacts").size()).isGreaterThan(2);

        JsonNode v2 = body(mvc.perform(post("/api/jobs/" + job + "/letters").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(request(cv, ",\"language\":\"fr\",\"useAi\":false"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.note").value("template")).andReturn());
        assertThat(v2.path("body").asString()).startsWith("Madame, Monsieur,");

        // The user edits the first version and adds a claim that the CV does not make: it is saved, with warnings.
        JsonNode v3 = body(mvc.perform(put("/api/documents/" + v1.path("id").asString()).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("text", "Dear team,\n\nI increased revenue by 95% at Globex using Java.\n\nRegards,\nJane Doe"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3)).andExpect(jsonPath("$.source").value("EDITED")).andReturn());
        assertThat(v3.path("warnings").valueStream().map(JsonNode::asString).toList()).contains("95", "Globex");
        String id3 = v3.path("id").asString();
        mvc.perform(get("/api/documents/" + id3).session(s)).andExpect(jsonPath("$.warnings.length()").value(2));

        mvc.perform(get("/api/jobs/" + job + "/documents").param("type", "LETTER").session(s)).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].version").value(3)).andExpect(jsonPath("$[2].version").value(1));

        byte[] pdf = mvc.perform(get("/api/documents/" + id3 + "/export").param("format", "pdf").session(s)).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", Matchers.containsString("motivation-letter-v3.pdf"))).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        byte[] docx = mvc.perform(get("/api/documents/" + id3 + "/export").param("format", "docx").session(s)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(docx, 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");
        mvc.perform(get("/api/documents/" + id3 + "/export").param("format", "txt").session(s)).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Globex")));
        mvc.perform(get("/api/documents/" + id3 + "/export").param("format", "eml").session(s)).andExpect(status().isUnprocessableEntity());

        mvc.perform(delete("/api/documents/" + id3).session(s).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/jobs/" + job + "/documents").param("type", "LETTER").session(s)).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void letterRequestsAreValidated() throws Exception {
        MockHttpSession s = register();
        search(s);
        String cv = createCv(s, JAVA_CV);
        String job = javaJobId(s);

        mvc.perform(post("/api/jobs/" + job + "/letters").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request(cv, ",\"language\":\"de\"")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value(Matchers.containsString("Supported languages: en, fr")));
        mvc.perform(post("/api/jobs/" + job + "/letters").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request(cv, ",\"tone\":\"SARCASTIC\"")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/jobs/" + job + "/letters").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/documents/languages").session(s)).andExpect(jsonPath("$[0]").value("en")).andExpect(jsonPath("$[1]").value("fr"));
    }

    @Test
    void nothingIsWrittenWhenTheCvHasNothingTrueToSayAboutTheOffer() throws Exception {
        MockHttpSession s = register();
        search(s);
        String garden = createCv(s, "Pat Green\npat@example.org\n\nExperience\nGardener - Parks Ltd\n2015 - 2020\n- Planted and watered trees across the city parks");

        mvc.perform(post("/api/jobs/" + javaJobId(s) + "/letters").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request(garden, "")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value(Matchers.containsString("nothing true to write")));
    }

    @Test
    void documentsBelongToTheirOwner() throws Exception {
        MockHttpSession alice = register();
        MockHttpSession bob = register();
        search(alice);
        String cv = createCv(alice, JAVA_CV);
        String job = javaJobId(alice);
        String id = body(postJson(alice, "/api/jobs/" + job + "/letters", request(cv, ""))).path("id").asString();

        mvc.perform(get("/api/documents/" + id).session(bob)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/documents/" + id).session(bob).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/documents/" + id + "/export").param("format", "pdf").session(bob)).andExpect(status().isNotFound());
        mvc.perform(put("/api/documents/" + id).session(bob).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"x\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/jobs/" + job + "/documents").param("type", "LETTER").session(bob)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/jobs/" + job + "/letters").session(bob).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request(cv, "")))
                .andExpect(status().isNotFound()); // a CV that is not Bob's
    }

    // ---- email ----

    @Test
    void emailDraftHasSubjectEvidenceAndIsNeverSent() throws Exception {
        MockHttpSession s = register();
        search(s);
        String cv = createCv(s, JAVA_CV);
        String job = javaJobId(s);

        JsonNode email = body(mvc.perform(post("/api/jobs/" + job + "/emails").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(request(cv, ",\"contactName\":\"Marie Dupont\",\"formality\":\"STANDARD\""))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("EMAIL")).andExpect(jsonPath("$.subject").value("Application: Java Developer (m/w/d) (Acme GmbH)")).andReturn());
        String text = email.path("body").asString();
        assertThat(text).startsWith("Dear Marie Dupont,").contains("What matches your posting:").contains("My CV and cover letter are attached.").contains("Kind regards,");
        assertThat(text).doesNotContain("@acme"); // no recipient address is ever invented
        String id = email.path("id").asString();

        byte[] eml = mvc.perform(get("/api/documents/" + id + "/export").param("format", "eml").param("to", "careers@acme-corp.io").session(s))
                .andExpect(status().isOk()).andExpect(content().contentType("message/rfc822")).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(eml, StandardCharsets.US_ASCII)).startsWith("X-Unsent: 1").contains("To: careers@acme-corp.io");
        mvc.perform(get("/api/documents/" + id + "/export").param("format", "eml").param("to", "bad address").session(s)).andExpect(status().isUnprocessableEntity());

        JsonNode edited = body(mvc.perform(put("/api/documents/" + id).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("subject", "Application: Java Developer", "text", text + "\nBest, Jane"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subject").value("Application: Java Developer")).andReturn());
        assertThat(edited.path("warnings").valueStream().map(JsonNode::asString).toList()).isEmpty();
    }

    @Test
    void anOfferInAnUnsupportedLanguageGetsADraftInASupportedOneAndSaysSo() throws Exception {
        MockHttpSession s = register();
        ingest.ingest("arbeitnow", null, List.of(new RawJob("de-1", "Java Entwickler (m/w/d)", "Muster GmbH", "Berlin", "DE", RemoteMode.UNKNOWN,
                "Wir suchen einen Entwickler für unser Team. Sie haben Erfahrung mit Java und Spring Boot und arbeiten mit uns an der Plattform. "
                        + "Anforderungen: Java, Spring Boot. Wir bieten Ihnen ein tolles Team und viele Aufgaben.",
                "https://www.arbeitnow.com/jobs/de-1", "https://www.arbeitnow.com/jobs/de-1", null, null, null, null, Instant.now(), null)));
        String cv = createCv(s, JAVA_CV);
        String job = javaJobId(s);

        mvc.perform(post("/api/jobs/" + job + "/letters").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request(cv, ",\"useAi\":false")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.jobLanguage").value("de")).andExpect(jsonPath("$.language").value("en"));
        mvc.perform(post("/api/jobs/" + job + "/letters").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request(cv, ",\"language\":\"fr\",\"useAi\":false")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.language").value("fr")).andExpect(jsonPath("$.jobLanguage").value("de"));
    }

    // ---- contacts ----

    @Test
    void contactsComeOnlyFromPublishedTextAndNeverFromGuesses() throws Exception {
        MockHttpSession s = register();
        String page = "https://www.arbeitnow.com/jobs/companies/acme/";
        ingest.ingest("arbeitnow", null, List.of(
                new RawJob("with-contact", "Cloud Engineer", "Acme Corp", "Paris", "FR", RemoteMode.UNKNOWN,
                        "Run our cloud. Send your application to talent@acme-corp.io. Contact: Marie Dupont, Talent Acquisition Manager", page + "1", page + "1/apply",
                        null, null, null, null, Instant.now(), null),
                new RawJob("no-contact", "Data Engineer", "Beta Corp", "Lyon", "FR", RemoteMode.UNKNOWN, "Build data pipelines. Apply on our website.",
                        page + "2", page + "2/apply", null, null, null, null, Instant.now(), null)));
        String withId = offers.findBySourceKeyAndExternalIdAndOwnerUserId("arbeitnow", "with-contact", null).orElseThrow().getId().toString();
        String noneId = offers.findBySourceKeyAndExternalIdAndOwnerUserId("arbeitnow", "no-contact", null).orElseThrow().getId().toString();

        mvc.perform(get("/api/jobs/" + withId + "/contacts").session(s)).andExpect(jsonPath("$.searched").value(false)).andExpect(jsonPath("$.hasEmail").value(false));
        mvc.perform(post("/api/jobs/" + withId + "/contacts/discover").session(s).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.searched").value(true)).andExpect(jsonPath("$.hasEmail").value(true))
                .andExpect(jsonPath("$.note").value("board")) // the offer lives on a job board, so no site is crawled as the employer
                .andExpect(jsonPath("$.contacts[?(@.kind=='EMAIL')].value").value("talent@acme-corp.io"))
                .andExpect(jsonPath("$.contacts[?(@.kind=='EMAIL')].verified").value(true))
                .andExpect(jsonPath("$.contacts[?(@.kind=='EMAIL')].sourceKind").value("AD_TEXT"))
                .andExpect(jsonPath("$.contacts[?(@.kind=='EMAIL')].sourceUrl").value(page + "1"))
                .andExpect(jsonPath("$.contacts[?(@.kind=='PERSON')].name").value("Marie Dupont"))
                .andExpect(jsonPath("$.contacts[?(@.kind=='PERSON')].title").value("Talent Acquisition Manager"));

        // An offer that publishes nothing yields nothing, plus the official application link.
        mvc.perform(post("/api/jobs/" + noneId + "/contacts/discover").session(s).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.contacts.length()").value(0)).andExpect(jsonPath("$.hasEmail").value(false))
                .andExpect(jsonPath("$.searched").value(true)).andExpect(jsonPath("$.applyUrl").value(page + "2/apply"));
        // Running it again does not duplicate anything.
        mvc.perform(post("/api/jobs/" + withId + "/contacts/discover").session(s).with(csrf())).andExpect(jsonPath("$.contacts.length()").value(2));
        mvc.perform(get("/api/jobs/" + UUID.randomUUID() + "/contacts").session(s)).andExpect(status().isNotFound());
    }

    @Test
    void employerPagesAreReadAndForeignDomainsStayUnverified() throws Exception {
        MockHttpSession s = register();
        MvcResult imported = postJson(s, "/api/jobs/import", "{\"url\":\"" + stub("/jobwithcontact") + "\"}");
        assertThat(imported.getResponse().getStatus()).as(imported.getResponse().getContentAsString()).isEqualTo(201);
        String id = body(imported).path("summary").path("id").asString();

        MvcResult r = mvc.perform(post("/api/jobs/" + id + "/contacts/discover").session(s).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.pagesRead").value(Matchers.greaterThanOrEqualTo(1))).andReturn();
        JsonNode contacts = body(r).path("contacts");
        JsonNode careers = contacts.valueStream().filter(c -> c.path("value").asString().equals("careers@initech-careers.io")).findFirst().orElseThrow();
        assertThat(careers.path("sourceKind").asString()).isEqualTo("OFFICIAL_PAGE");
        assertThat(careers.path("verified").asBoolean()).isFalse(); // the address is on another domain than the page
        assertThat(contacts.valueStream().map(c -> c.path("value").asString())).doesNotContain("press@initech-careers.io");
        assertThat(contacts.valueStream().anyMatch(c -> c.path("kind").asString().equals("URL") && c.path("value").asString().endsWith("/apply/9000"))).isTrue();

        MockHttpSession other = register();
        mvc.perform(get("/api/jobs/" + id + "/contacts").session(other)).andExpect(status().isNotFound());
        mvc.perform(post("/api/jobs/" + id + "/contacts/discover").session(other).with(csrf())).andExpect(status().isNotFound());
    }
}
