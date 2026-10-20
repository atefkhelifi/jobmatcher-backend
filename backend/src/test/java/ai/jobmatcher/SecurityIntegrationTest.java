package ai.jobmatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ai.jobmatcher.jobs.JobOfferRepository;
import ai.jobmatcher.jobs.OfferIngestService;
import ai.jobmatcher.jobs.RawJob;
import ai.jobmatcher.jobs.RemoteMode;
import ai.jobmatcher.jobs.RetentionService;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Transport-level protections, data export completeness and retention, against the real application and database. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class SecurityIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JobOfferRepository offers;
    @Autowired OfferIngestService ingest;
    @Autowired RetentionService retention;

    private MockHttpSession register() throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"sec-%s@example.org","password":"correct horse battery","displayName":"Sec Tester","consent":true}
                """.formatted(UUID.randomUUID()))).andExpect(status().isCreated()).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void everyApiAnswerCarriesBrowserHardeningHeaders() throws Exception {
        MockHttpSession s = register();

        for (String path : List.of("/api/status", "/api/auth/me", "/api/cvs", "/api/jobs", "/api/applications")) {
            mvc.perform(get(path).session(s)).andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("X-Frame-Options", "DENY")).andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(header().string("Content-Security-Policy", Matchers.containsString("default-src 'none'")))
                    .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")));
        }
        // Unauthenticated callers get a plain 401, not a browser credential prompt.
        mvc.perform(get("/api/cvs")).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void managementEndpointsOtherThanHealthAreNotReachable() throws Exception {
        MockHttpSession s = register();

        for (String path : List.of("/actuator/env", "/actuator/beans", "/actuator/heapdump", "/actuator/mappings", "/actuator/configprops")) {
            assertThat(mvc.perform(get(path).session(s)).andReturn().getResponse().getStatus()).as(path).isIn(401, 403, 404);
        }
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/index.html")).andExpect(status().isUnauthorized()); // nothing but the API is served here
    }

    @Test
    void stateChangingRequestsNeedTheCsrfTokenEvenWithAValidSession() throws Exception {
        MockHttpSession s = register();

        mvc.perform(post("/api/applications").session(s).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/profile").session(s).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/account").session(s).contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").session(s)).andExpect(status().isForbidden());
    }

    @Test
    void loginDoesNotRevealWhetherAnAccountExists() throws Exception {
        MockHttpSession s = register();
        String email = jdbc.queryForObject("select email from app_user order by created_at desc limit 1", String.class);

        MvcResult known = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"wrong wrong wrong\"}")).andExpect(status().isUnauthorized()).andReturn();
        MvcResult unknown = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody-" + UUID.randomUUID() + "@example.org\",\"password\":\"wrong wrong wrong\"}")).andExpect(status().isUnauthorized()).andReturn();

        assertThat(body(known).path("detail").asString()).isEqualTo(body(unknown).path("detail").asString()).isEqualTo("Invalid email or password.");
        assertThat(s).isNotNull();
    }

    @Test
    void errorsNeverLeakStackTracesOrInternals() throws Exception {
        MockHttpSession s = register();

        for (MvcResult r : List.of(
                mvc.perform(get("/api/cvs/not-a-uuid").session(s)).andReturn(),
                mvc.perform(post("/api/cvs/text").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{broken")).andReturn(),
                mvc.perform(get("/api/jobs").param("size", "abc").session(s)).andReturn())) {
            String text = r.getResponse().getContentAsString();
            assertThat(r.getResponse().getStatus()).isBetween(400, 499);
            assertThat(text).doesNotContain("Exception").doesNotContain("at ai.jobmatcher").doesNotContain("org.springframework").doesNotContain("jdbc");
        }
    }

    // ---- data export ----

    @Test
    void theExportContainsEverythingStoredAboutTheUser() throws Exception {
        MockHttpSession s = register();
        mvc.perform(post("/api/searches").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Mine\",\"keywords\":\"java\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/cvs/text").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                java.util.Map.of("label", "CV", "text", "Jane Doe\njane@example.org\n\nExperience\nJava Developer - Acme\n2020 - 2023\n- Built Java services\n\nSkills\nJava, Spring Boot")))).andExpect(status().isCreated());
        MvcResult app = mvc.perform(post("/api/applications").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Platform Engineer\",\"company\":\"Initech\"}")).andExpect(status().isCreated()).andReturn();
        String id = body(app).path("summary").path("id").asString();
        mvc.perform(post("/api/applications/" + id + "/notes").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Private note\"}"));
        mvc.perform(post("/api/applications/" + id + "/contacts").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Marie Dupont\"}"));

        MvcResult exported = mvc.perform(get("/api/account/export").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.account.email").isNotEmpty()).andExpect(jsonPath("$.cvs.length()").value(1))
                .andExpect(jsonPath("$.savedSearches[0].name").value("Mine")).andExpect(jsonPath("$.applications.length()").value(1))
                .andExpect(jsonPath("$.applications[0].summary.title").value("Platform Engineer"))
                .andExpect(jsonPath("$.applications[0].contacts[0].name").value("Marie Dupont"))
                .andExpect(jsonPath("$.applications[0].timeline[1].note").value("Private note")).andExpect(jsonPath("$.documents").isArray()).andReturn();
        assertThat(exported.getResponse().getContentAsString()).doesNotContain("password");
        // Another user never sees it.
        mvc.perform(get("/api/account/export").session(register())).andExpect(jsonPath("$.applications.length()").value(0));
    }

    // ---- retention ----

    @Test
    void oldExpiredOffersAreRemovedUnlessSomeoneStillReliesOnThem() throws Exception {
        MockHttpSession s = register();
        String page = "https://www.arbeitnow.com/jobs/retention/";
        ingest.ingest("arbeitnow", null, List.of(offer("old-plain", page + "1"), offer("old-tracked", page + "2"), offer("recent-expired", page + "3"),
                offer("old-active", page + "4")));
        UUID tracked = offers.findBySourceKeyAndExternalIdAndOwnerUserId("arbeitnow", "old-tracked", null).orElseThrow().getId();
        mvc.perform(post("/api/applications").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"jobId\":\"" + tracked + "\"}")).andExpect(status().isCreated());
        jdbc.update("update job_offer set status = 'EXPIRED', last_seen_at = ? where external_id in ('old-plain', 'old-tracked')",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(400))));
        jdbc.update("update job_offer set status = 'EXPIRED', last_seen_at = ? where external_id = 'recent-expired'", java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(10))));
        jdbc.update("update job_offer set last_seen_at = ? where external_id = 'old-active'", java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(400))));

        int removed = retention.purgeExpiredOffers();

        assertThat(removed).isEqualTo(1);
        assertThat(jdbc.queryForList("select external_id from job_offer where external_id like 'old-%' or external_id like 'recent-%' order by external_id", String.class))
                .containsExactly("old-active", "old-tracked", "recent-expired");
        // The tracked application keeps working.
        mvc.perform(get("/api/applications").session(s)).andExpect(jsonPath("$[0].title").value("Offer old-tracked"));
    }

    private static RawJob offer(String id, String url) {
        return new RawJob(id, "Offer " + id, "Retention Co " + id, "Paris", "FR", RemoteMode.UNKNOWN, "Requirements\n- Java", url, url, null, null, null, null, Instant.now(), null);
    }
}
