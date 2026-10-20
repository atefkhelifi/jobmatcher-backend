package ai.jobmatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ai.jobmatcher.jobs.JobOffer;
import ai.jobmatcher.jobs.JobOfferRepository;
import ai.jobmatcher.jobs.OfferIngestService;
import ai.jobmatcher.jobs.OfferVerifier;
import ai.jobmatcher.jobs.RawJob;
import ai.jobmatcher.jobs.RemoteMode;
import ai.jobmatcher.jobs.SourceHealthRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

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

/** Discovery end to end against a local stub of the job sources, on a real PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class JobsIntegrationTest {

    private static final HttpServer STUB = startStub();

    private static HttpServer startStub() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/api/job-board-api", ex -> reply(ex, 200, "application/json", read("arbeitnow.json")));
            s.createContext("/job", ex -> reply(ex, 200, "text/html", read("jobposting.html")));
            s.createContext("/nojob", ex -> reply(ex, 200, "text/html", "<html><body>Nothing structured</body></html>"));
            s.createContext("/blocked", ex -> reply(ex, 200, "text/html", read("jobposting.html")));
            s.createContext("/ok", ex -> reply(ex, 200, "text/html", "open"));
            s.createContext("/gone", ex -> reply(ex, 410, "text/html", "gone"));
            s.createContext("/robots.txt", ex -> reply(ex, 200, "text/plain", "User-agent: *\nDisallow: /blocked\n"));
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/fixtures", name), StandardCharsets.UTF_8);
    }

    private static void reply(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, b.length);
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

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JobOfferRepository offers;
    @Autowired SourceHealthRepository health;
    @Autowired OfferIngestService ingest;
    @Autowired OfferVerifier verifier;

    @BeforeEach
    void clean() {
        offers.deleteAll();
        health.deleteAll();
    }

    private MockHttpSession register() throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"correct horse battery","displayName":"Tester","consent":true}
                """.formatted("jobs-" + UUID.randomUUID() + "@example.org"))).andExpect(status().isCreated()).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private void search(MockHttpSession s) throws Exception {
        mvc.perform(post("/api/searches/run").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void requiresLogin() throws Exception {
        mvc.perform(get("/api/jobs")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/searches/run").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void searchStoresOffersAndCollapsesDuplicatesWithinOneRun() throws Exception {
        MockHttpSession s = register();

        mvc.perform(post("/api/searches/run").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.duplicates").value(1))
                .andExpect(jsonPath("$.sources[0].source").value("arbeitnow"))
                .andExpect(jsonPath("$.sources[0].fetched").value(3));

        MvcResult list = mvc.perform(get("/api/jobs").session(s)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andReturn();
        List<String> titles = body(list).path("items").valueStream().map(n -> n.path("title").asString()).toList();
        assertThat(titles).containsExactlyInAnyOrder("Java Developer (m/w/d)", "Remote Python Engineer");

        // Running again updates, never duplicates.
        mvc.perform(post("/api/searches/run").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.created").value(0)).andExpect(jsonPath("$.updated").value(3));
        mvc.perform(get("/api/jobs").session(s)).andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void searchHonoursKeywordsAndRemoteFilterAtTheSource() throws Exception {
        MockHttpSession s = register();

        mvc.perform(post("/api/searches/run").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"keywords\":\"python\",\"remoteOnly\":true}"))
                .andExpect(jsonPath("$.created").value(1)).andExpect(jsonPath("$.sources[0].fetched").value(1));
    }

    @Test
    void listFiltersSortsAndPaginates() throws Exception {
        MockHttpSession s = register();
        search(s);

        mvc.perform(get("/api/jobs").param("q", "python").session(s)).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].company").value("Globex"))
                .andExpect(jsonPath("$.items[0].salaryMin").doesNotExist())
                .andExpect(jsonPath("$.items[0].remoteMode").value("REMOTE"));
        mvc.perform(get("/api/jobs").param("remote", "REMOTE").session(s)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/jobs").param("location", "berlin").session(s)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/jobs").param("q", "100%").session(s)).andExpect(jsonPath("$.total").value(0)); // wildcard is escaped
        mvc.perform(get("/api/jobs").param("minSalary", "40000").param("includeUnknownSalary", "false").session(s))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/jobs").param("minSalary", "40000").session(s)).andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/jobs").param("sort", "company").session(s)).andExpect(jsonPath("$.items[0].company").value("Acme GmbH"));
        mvc.perform(get("/api/jobs").param("sort", "newest").session(s)).andExpect(jsonPath("$.items[0].company").value("Globex"));
        mvc.perform(get("/api/jobs").param("sort", "bogus").session(s)).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/jobs").param("size", "1").param("page", "1").session(s)).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void detailShowsUnknownFieldsAsNullAndLinksToTheSource() throws Exception {
        MockHttpSession s = register();
        search(s);
        String id = body(mvc.perform(get("/api/jobs").param("q", "python").session(s)).andReturn()).path("items").get(0).path("id").asString();

        mvc.perform(get("/api/jobs/" + id).session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Design data pipelines in Python and Kafka."))
                .andExpect(jsonPath("$.summary.url").value("https://www.arbeitnow.com/jobs/companies/globex/remote-python-engineer-1002"))
                .andExpect(jsonPath("$.summary.sourceName").value("Arbeitnow"))
                .andExpect(jsonPath("$.summary.salaryMax").doesNotExist())
                .andExpect(jsonPath("$.summary.validThrough").doesNotExist())
                .andExpect(jsonPath("$.sourceTermsUrl").value("https://www.arbeitnow.com/blog/job-board-api"));
        mvc.perform(get("/api/jobs/" + UUID.randomUUID()).session(s)).andExpect(status().isNotFound());
    }

    @Test
    void sourceHealthReportsSuccessAndMissingConfiguration() throws Exception {
        MockHttpSession s = register();
        search(s);

        mvc.perform(get("/api/sources").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='arbeitnow')].state").value("healthy"))
                .andExpect(jsonPath("$[?(@.key=='arbeitnow')].activeOffers").value(2))
                .andExpect(jsonPath("$[?(@.key=='francetravail')].enabled").value(false))
                .andExpect(jsonPath("$[?(@.key=='francetravail')].state").value("disabled"))
                .andExpect(jsonPath("$[?(@.key=='greenhouse')].disabledReason").isNotEmpty());
    }

    @Test
    void savedSearchesAreOwnedAndRunnable() throws Exception {
        MockHttpSession alice = register();
        MockHttpSession bob = register();

        MvcResult created = mvc.perform(post("/api/searches").session(alice).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Python remote\",\"keywords\":\"python\",\"remoteOnly\":true,\"sourceKeys\":[\"arbeitnow\",\"nonsense\"],\"autoRun\":true,\"intervalHours\":12}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.sourceKeys.length()").value(1)).andReturn();
        String id = body(created).path("id").asString();

        mvc.perform(post("/api/searches/" + id + "/run").session(bob).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/searches/" + id).session(bob).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/searches").session(bob)).andExpect(jsonPath("$.length()").value(0));

        mvc.perform(post("/api/searches/" + id + "/run").session(alice).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));
        mvc.perform(get("/api/searches").session(alice)).andExpect(jsonPath("$[0].lastNewCount").value(1))
                .andExpect(jsonPath("$[0].lastRunAt").isNotEmpty());
        mvc.perform(post("/api/searches").session(alice).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"intervalHours\":999}")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/searches/" + id).session(alice).with(csrf())).andExpect(status().isNoContent());
    }

    @Test
    void importedPagesArePrivateToTheUserWhoSubmittedThem() throws Exception {
        MockHttpSession alice = register();
        MockHttpSession bob = register();

        MvcResult imported = mvc.perform(post("/api/jobs/import").session(alice).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + stub("/job") + "\"}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.summary.title").value("Backend Engineer"))
                .andExpect(jsonPath("$.summary.company").value("Initech SAS"))
                .andExpect(jsonPath("$.summary.salaryMin").value(50000))
                .andExpect(jsonPath("$.summary.remoteMode").value("REMOTE"))
                .andExpect(jsonPath("$.summary.submittedByYou").value(true)).andReturn();
        String id = body(imported).path("summary").path("id").asString();

        mvc.perform(get("/api/jobs/" + id).session(bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/jobs").session(bob)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/jobs").session(alice)).andExpect(jsonPath("$.total").value(1));

        // Importing again does not create a second copy.
        mvc.perform(post("/api/jobs/import").session(alice).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + stub("/job") + "\"}")).andExpect(status().isCreated());
        mvc.perform(get("/api/jobs").session(alice)).andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void importExplainsPagesWithoutStructuredDataAndRespectsRobots() throws Exception {
        MockHttpSession s = register();

        mvc.perform(post("/api/jobs/import").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + stub("/nojob") + "\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("No structured job posting")));
        mvc.perform(post("/api/jobs/import").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + stub("/blocked") + "\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("robots.txt")));
        mvc.perform(post("/api/jobs/import").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"ftp://example.com/job\"}")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void expiredOffersAreDetectedHiddenAndReopenedWhenSeenAgain() throws Exception {
        MockHttpSession s = register();
        Instant now = Instant.now();
        ingest.ingest("arbeitnow", null, List.of(
                raw("deadline", "Role with deadline", stub("/ok"), now.minus(Duration.ofDays(1))),
                raw("gone", "Role that vanished", stub("/gone"), null),
                raw("open", "Role still open", stub("/ok"), null)));

        assertThat(verifier.verifyBatch()).isEqualTo(2);

        mvc.perform(get("/api/jobs").session(s)).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Role still open"));
        mvc.perform(get("/api/jobs").param("includeExpired", "true").session(s)).andExpect(jsonPath("$.total").value(3));
        JobOffer open = offers.findBySourceKeyAndExternalIdAndOwnerUserId("arbeitnow", "open", null).orElseThrow();
        assertThat(open.getStatus()).isEqualTo(JobOffer.Status.ACTIVE);
        assertThat(open.getLastVerifiedAt()).isNotNull();

        // The source lists the vanished role again: it is open again.
        ingest.ingest("arbeitnow", null, List.of(raw("gone", "Role that vanished", stub("/ok"), null)));
        mvc.perform(get("/api/jobs").session(s)).andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void networkErrorsDoNotExpireOffers() {
        ingest.ingest("arbeitnow", null, List.of(raw("offline", "Role on a dead host", "http://127.0.0.1:1/job", null)));

        assertThat(verifier.verifyBatch()).isZero();
        assertThat(offers.findBySourceKeyAndExternalIdAndOwnerUserId("arbeitnow", "offline", null).orElseThrow().getStatus())
                .isEqualTo(JobOffer.Status.ACTIVE);
    }

    @Test
    void sameRoleFromTwoSourcesIsShownOnce() throws Exception {
        MockHttpSession s = register();
        ingest.ingest("arbeitnow", null, List.of(raw("a-1", "Data Engineer (m/f/d)", stub("/ok"), null)));
        ingest.ingest("greenhouse", null, List.of(raw("g-9", "Data Engineer", stub("/ok"), null)));

        mvc.perform(get("/api/jobs").param("q", "data engineer").session(s)).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].sourceKey").value("arbeitnow"));
    }

    // ---- matching ----

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

    private String createCv(MockHttpSession s) throws Exception {
        MvcResult r = mvc.perform(post("/api/cvs/text").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("label", "Backend", "text", JAVA_CV)))).andExpect(status().isCreated()).andReturn();
        return body(r).path("summary").path("id").asString();
    }

    private void runMatches(MockHttpSession s, String cvId) throws Exception {
        mvc.perform(post("/api/matches/run").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"cvId\":\"" + cvId + "\"}")).andExpect(status().isOk());
    }

    @Test
    void jobsAreScoredRankedAndExplainedAgainstTheChosenCv() throws Exception {
        MockHttpSession s = register();
        search(s);
        String cv = createCv(s);

        mvc.perform(post("/api/matches/run").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"cvId\":\"" + cv + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.computed").value(2));
        // Nothing changed, so a second run reuses every result.
        mvc.perform(post("/api/matches/run").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"cvId\":\"" + cv + "\"}"))
                .andExpect(jsonPath("$.computed").value(0)).andExpect(jsonPath("$.reused").value(2));

        MvcResult ranked = mvc.perform(get("/api/jobs").param("sort", "match").param("cvId", cv).session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items[0].title").value("Java Developer (m/w/d)"))
                .andExpect(jsonPath("$.items[0].matchScore").isNumber()).andExpect(jsonPath("$.items[0].matchCoverage").isNumber()).andReturn();
        JsonNode items = body(ranked).path("items");
        assertThat(items.get(0).path("matchScore").asInt()).isGreaterThan(items.get(1).path("matchScore").asInt());

        mvc.perform(get("/api/jobs").param("cvId", cv).param("minScore", "1").session(s))
                .andExpect(jsonPath("$.items[?(@.matchScore < 1)]").isEmpty());
        mvc.perform(get("/api/jobs").param("cvId", cv).param("minScore", "101").session(s)).andExpect(jsonPath("$.total").value(0));

        String javaJob = items.get(0).path("id").asString();
        mvc.perform(get("/api/jobs/" + javaJob + "/match").param("cvId", cv).session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.report.components.length()").value(8))
                .andExpect(jsonPath("$.report.insufficientData").value(false))
                .andExpect(jsonPath("$.weights.requiredSkills").value(30))
                .andExpect(jsonPath("$.report.skills[?(@.skill=='Java')].matched").value(true));
    }

    @Test
    void editingTheCvOrPreferencesOutdatesStoredScores() throws Exception {
        MockHttpSession s = register();
        search(s);
        String cv = createCv(s);
        runMatches(s, cv);
        mvc.perform(get("/api/jobs").param("cvId", cv).session(s)).andExpect(jsonPath("$.items[0].matchScore").isNumber());

        // Saving a reviewed profile changes the CV version, so earlier scores are no longer shown.
        JsonNode detail = body(mvc.perform(get("/api/cvs/" + cv).session(s)).andReturn());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/cvs/" + cv + "/profile").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("label", "Backend v2", "parsed", json.readValue(json.writeValueAsString(detail.path("parsed")), java.util.Map.class)))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/jobs").param("cvId", cv).session(s)).andExpect(jsonPath("$.items[0].matchScore").doesNotExist());

        runMatches(s, cv);
        mvc.perform(get("/api/jobs").param("cvId", cv).session(s)).andExpect(jsonPath("$.items[0].matchScore").isNumber());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/profile").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seniority\":\"junior\",\"willingToRelocate\":false,\"notifyNewMatches\":true,\"notifyReminders\":true}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/jobs").param("cvId", cv).session(s)).andExpect(jsonPath("$.items[0].matchScore").doesNotExist());
    }

    @Test
    void matchingNeverTouchesAnotherUsersCv() throws Exception {
        MockHttpSession alice = register();
        MockHttpSession bob = register();
        search(alice);
        String aliceCv = createCv(alice);
        runMatches(alice, aliceCv);
        String job = body(mvc.perform(get("/api/jobs").session(alice)).andReturn()).path("items").get(0).path("id").asString();

        mvc.perform(post("/api/matches/run").session(bob).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"cvId\":\"" + aliceCv + "\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/jobs/" + job + "/match").param("cvId", aliceCv).session(bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/jobs").param("cvId", aliceCv).session(bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/jobs").param("sort", "match").session(alice)).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/matches/run").session(alice).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    private static RawJob raw(String id, String title, String url, Instant validThrough) {
        return new RawJob(id, title, "Same Corp", "Paris", "FR", RemoteMode.UNKNOWN, "A description long enough to store.", url, url,
                null, null, null, null, Instant.now(), validThrough);
    }
}
