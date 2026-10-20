package ai.jobmatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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

/** Comparing two CV versions against a job, on a real PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ComparisonIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JobOfferRepository offers;
    @Autowired OfferIngestService ingest;

    private static final String CV_A = """
            Jane Doe
            jane.doe@example.org

            Profile
            Backend engineer focused on Java services.

            Experience
            Senior Java Developer - Northwind Labs
            Jan 2019 - Present
            - Designed REST APIs with Spring Boot used by 12 internal teams

            Skills
            Java, Spring Boot, PostgreSQL
            """;

    private static final String CV_B = """
            Jane Doe
            jane.doe@example.org

            Profile
            Engineer.

            Experience
            Senior Java Developer - Northwind Labs
            Jan 2020 - Present
            - Worked on services

            Skills
            Java
            """;

    private String jobId;

    @BeforeEach
    void catalog() {
        offers.deleteAll();
        ingest.ingest("arbeitnow", null, List.of(new RawJob("cmp-1", "Java Developer", "Acme GmbH", "Berlin", "DE", RemoteMode.UNKNOWN,
                "Requirements\n- Java\n- Spring Boot\n- Docker\nNice to have\n- PostgreSQL", "https://www.arbeitnow.com/jobs/cmp-1",
                "https://www.arbeitnow.com/jobs/cmp-1", null, null, null, null, Instant.now(), null)));
        jobId = offers.findBySourceKeyAndExternalIdAndOwnerUserId("arbeitnow", "cmp-1", null).orElseThrow().getId().toString();
    }

    private MockHttpSession register() throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"cmp-%s@example.org","password":"correct horse battery","displayName":"Jane Doe","consent":true}
                """.formatted(UUID.randomUUID()))).andExpect(status().isCreated()).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private String createCv(MockHttpSession s, String label, String text) throws Exception {
        MvcResult r = mvc.perform(post("/api/cvs/text").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("label", label, "text", text)))).andExpect(status().isCreated()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).path("summary").path("id").asString();
    }

    private JsonNode compare(MockHttpSession s, String a, String b, String job, int expected) throws Exception {
        var req = get("/api/cv-comparison").param("a", a).param("b", b).session(s);
        if (job != null) {
            req = req.param("jobId", job);
        }
        MvcResult r = mvc.perform(req).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expected);
        return json.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void comparesReadabilityCoverageAndFactsForTwoVersions() throws Exception {
        MockHttpSession s = register();
        String a = createCv(s, "Detailed", CV_A);
        String b = createCv(s, "Short", CV_B);

        JsonNode c = compare(s, a, b, jobId, 200);
        assertThat(c.path("job").path("title").asString()).isEqualTo("Java Developer");
        assertThat(c.path("a").path("label").asString()).isEqualTo("Detailed");
        assertThat(c.path("a").path("readability").path("quantifiedBulletShare").asDouble()).isEqualTo(100.0);
        assertThat(c.path("b").path("readability").path("quantifiedBulletShare").asDouble()).isZero();
        assertThat(c.path("a").path("coverage").path("requiredCovered").asInt())
                .isGreaterThan(c.path("b").path("coverage").path("requiredCovered").asInt());
        assertThat(c.path("a").path("coverage").path("missingRequired").toString()).contains("Docker");
        assertThat(c.path("differences").toString()).contains("EXPERIENCE_DATES_DIFFER");
        assertThat(c.path("skillsOnlyInA").toString()).contains("Spring Boot");
        assertThat(c.path("a").path("matchScore").asInt()).isGreaterThan(c.path("b").path("matchScore").asInt());
    }

    @Test
    void worksWithoutAJobAndRefusesAnotherUsersCvs() throws Exception {
        MockHttpSession s = register();
        String a = createCv(s, "One", CV_A);
        String b = createCv(s, "Two", CV_B);
        JsonNode c = compare(s, a, b, null, 200);
        assertThat(c.path("job").isNull()).isTrue();
        assertThat(c.path("a").path("coverage").isNull()).isTrue();

        compare(s, a, a, null, 422);
        MockHttpSession other = register();
        compare(other, a, b, null, 404);
    }
}
