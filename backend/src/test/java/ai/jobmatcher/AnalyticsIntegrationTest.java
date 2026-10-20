package ai.jobmatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ai.jobmatcher.jobs.JobOfferRepository;
import ai.jobmatcher.jobs.OfferIngestService;
import ai.jobmatcher.jobs.RawJob;
import ai.jobmatcher.jobs.RemoteMode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Analytics over the user's own applications, on a real PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class AnalyticsIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JobOfferRepository offers;
    @Autowired OfferIngestService ingest;

    @BeforeEach
    void catalog() {
        offers.deleteAll();
        ingest.ingest("arbeitnow", null, List.of(
                new RawJob("an-1", "Java Developer (m/f/d)", "Acme GmbH", "Berlin", "DE", RemoteMode.UNKNOWN, "Requirements\n- Java\n- Spring Boot",
                        "https://www.arbeitnow.com/jobs/an-1", "https://www.arbeitnow.com/jobs/an-1", null, null, null, null, Instant.now(), null),
                new RawJob("an-2", "Java Developer", "Globex", "Paris", "FR", RemoteMode.UNKNOWN, "Requirements\n- Java\n- Docker",
                        "https://www.arbeitnow.com/jobs/an-2", "https://www.arbeitnow.com/jobs/an-2", null, null, null, null, Instant.now(), null),
                new RawJob("an-3", "Data Engineer", "Initech", "Berlin", "DE", RemoteMode.UNKNOWN, "Requirements\n- Python",
                        "https://www.arbeitnow.com/jobs/an-3", "https://www.arbeitnow.com/jobs/an-3", null, null, null, null, Instant.now(), null)));
    }

    private MockHttpSession register() throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"stats-%s@example.org","password":"correct horse battery","displayName":"Jane Doe","consent":true}
                """.formatted(UUID.randomUUID()))).andExpect(status().isCreated()).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private MvcResult send(MockHttpSession s, String url, String body) throws Exception {
        return mvc.perform(post(url).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private String track(MockHttpSession s, String ext) throws Exception {
        String job = offers.findBySourceKeyAndExternalIdAndOwnerUserId("arbeitnow", ext, null).orElseThrow().getId().toString();
        MvcResult r = send(s, "/api/applications", "{\"jobId\":\"" + job + "\"}");
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return body(r).path("summary").path("id").asString();
    }

    @Test
    void anEmptyAccountHasNoRatesAndIsFlaggedAsASmallSample() throws Exception {
        MockHttpSession s = register();
        JsonNode v = body(mvc.perform(get("/api/analytics").session(s)).andExpect(status().isOk()).andReturn());
        assertThat(v.path("overview").path("tracked").asInt()).isZero();
        assertThat(v.path("overview").path("interviewRate").isNull()).isTrue();
        assertThat(v.path("overview").path("smallSample").asBoolean()).isTrue();
        assertThat(v.path("bestCvNote").asString()).isEqualTo("NOT_ENOUGH_DATA");
        assertThat(v.path("bestCvId").isNull()).isTrue();
    }

    @Test
    void countsOnlyTheUsersOwnApplicationsAndDerivesTheFunnel() throws Exception {
        MockHttpSession s = register();
        String a1 = track(s, "an-1");
        String a2 = track(s, "an-2");
        track(s, "an-3"); // stays discovered
        send(s, "/api/applications/" + a1 + "/status", "{\"status\":\"APPLIED\"}");
        send(s, "/api/applications/" + a1 + "/status", "{\"status\":\"INTERVIEW\"}");
        send(s, "/api/applications/" + a2 + "/status", "{\"status\":\"APPLIED\"}");
        send(s, "/api/applications/" + a2 + "/status", "{\"status\":\"REJECTED\"}");

        JsonNode v = body(mvc.perform(get("/api/analytics").session(s)).andExpect(status().isOk()).andReturn());
        JsonNode o = v.path("overview");
        assertThat(o.path("tracked").asInt()).isEqualTo(3);
        assertThat(o.path("applied").asInt()).isEqualTo(2);
        assertThat(o.path("interviews").asInt()).isEqualTo(1);
        assertThat(o.path("rejectedAfterApplying").asInt()).isEqualTo(1);
        assertThat(o.path("interviewRate").asDouble()).isEqualTo(50.0);
        assertThat(o.path("smallSample").asBoolean()).isTrue();

        assertThat(v.path("byCountry").toString()).contains("\"DE\"").contains("\"FR\"");
        // The marker "(m/f/d)" is dropped, so both Java postings are one role.
        assertThat(v.path("byRole").get(0).path("key").asString()).isEqualTo("java developer");
        assertThat(v.path("byRole").get(0).path("count").asInt()).isEqualTo(2);
        assertThat(v.path("skills").get(0).path("key").asString()).isEqualTo("Java");
        assertThat(v.path("skills").get(0).path("count").asInt()).isEqualTo(2);
        assertThat(v.path("timePerStage").toString()).contains("APPLIED");

        MockHttpSession other = register();
        JsonNode nothing = body(mvc.perform(get("/api/analytics").session(other)).andReturn());
        assertThat(nothing.path("overview").path("tracked").asInt()).isZero();
    }

    @Test
    void requiresSignIn() throws Exception {
        mvc.perform(get("/api/analytics")).andExpect(status().isUnauthorized());
    }
}
