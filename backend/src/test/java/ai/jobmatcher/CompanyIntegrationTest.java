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

/** What the platform knows about an employer, on a real PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class CompanyIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JobOfferRepository offers;
    @Autowired OfferIngestService ingest;

    private String firstJob;
    private String noCompanyJob;

    private RawJob raw(String id, String title, String company, String desc) {
        return new RawJob(id, title, company, "Berlin", "DE", RemoteMode.UNKNOWN, desc, "https://www.arbeitnow.com/jobs/" + id,
                "https://www.arbeitnow.com/jobs/" + id, null, null, null, null, Instant.now(), null);
    }

    @BeforeEach
    void catalog() {
        offers.deleteAll();
        String long_ = "Requirements\n- Java and Spring Boot\nWe offer a hybrid schedule and a learning budget. ".repeat(6);
        ingest.ingest("arbeitnow", null, List.of(raw("co-1", "Java Developer", "Acme GmbH", long_), raw("co-2", "QA Engineer", "ACME", long_),
                raw("co-3", "Assistant", "Acme", long_ + " Pay a registration fee to start."), raw("co-4", "Other", "Globex", long_),
                raw("co-5", "Anonymous role", null, long_)));
        firstJob = offers.findBySourceKeyAndExternalIdAndOwnerUserId("arbeitnow", "co-1", null).orElseThrow().getId().toString();
        noCompanyJob = offers.findBySourceKeyAndExternalIdAndOwnerUserId("arbeitnow", "co-5", null).orElseThrow().getId().toString();
    }

    private MockHttpSession register() throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"co-%s@example.org","password":"correct horse battery","displayName":"Jane Doe","consent":true}
                """.formatted(UUID.randomUUID()))).andExpect(status().isCreated()).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private JsonNode company(MockHttpSession s, String job) throws Exception {
        MvcResult r = mvc.perform(get("/api/jobs/" + job + "/company").session(s)).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void groupsOffersUnderTheSameCompanyAcrossSpellingsAndCountsWarnings() throws Exception {
        MockHttpSession s = register();
        JsonNode c = company(s, firstJob);
        assertThat(c.path("named").asBoolean()).isTrue();
        assertThat(c.path("openOffers").asInt()).isEqualTo(3); // Acme GmbH, ACME, Acme
        assertThat(c.path("bySource").path("arbeitnow").asInt()).isEqualTo(3);
        assertThat(c.path("byCountry").path("DE").asInt()).isEqualTo(3);
        assertThat(c.path("offersWithWarnings").asInt()).isEqualTo(1);
        assertThat(c.path("offers").size()).isEqualTo(3);
        assertThat(c.path("yourHistory").path("applications").asInt()).isZero();
    }

    @Test
    void includesTheUsersOwnHistoryWithTheCompanyAndNothingElse() throws Exception {
        MockHttpSession s = register();
        mvc.perform(post("/api/applications").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"jobId\":\"" + firstJob + "\"}"))
                .andExpect(status().isCreated());
        JsonNode c = company(s, firstJob);
        assertThat(c.path("yourHistory").path("applications").asInt()).isEqualTo(1);
        assertThat(c.path("yourHistory").path("byStatus").path("TO_REVIEW").asInt()).isEqualTo(1);
        JsonNode other = company(register(), firstJob);
        assertThat(other.path("yourHistory").path("applications").asInt()).isZero();
    }

    @Test
    void anOfferWithoutACompanyNameHasNothingToReport() throws Exception {
        JsonNode c = company(register(), noCompanyJob);
        assertThat(c.path("named").asBoolean()).isFalse();
        assertThat(c.path("openOffers").asInt()).isZero();
    }
}
