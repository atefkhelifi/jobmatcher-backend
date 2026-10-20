package ai.jobmatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import ai.jobmatcher.jobs.SearchConfiguration;
import ai.jobmatcher.notifications.AlertService;
import ai.jobmatcher.notifications.NotificationDispatcher;

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

/** Job alerts and reminder notifications, on a real PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class NotificationsIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JobOfferRepository offers;
    @Autowired OfferIngestService ingest;
    @Autowired AlertService alerts;
    @Autowired NotificationDispatcher dispatcher;

    private static final String CV = """
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

    @BeforeEach
    void clean() {
        offers.deleteAll();
    }

    private JsonNode register(MockHttpSession[] session) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"notify-%s@example.org","password":"correct horse battery","displayName":"Jane Doe","consent":true}
                """.formatted(UUID.randomUUID()))).andExpect(status().isCreated()).andReturn();
        session[0] = (MockHttpSession) r.getRequest().getSession(false);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private void ingestJob(String id, String title, String desc) {
        ingest.ingest("arbeitnow", null, List.of(new RawJob(id, title, "Acme GmbH", "Berlin", "DE", RemoteMode.UNKNOWN, desc,
                "https://www.arbeitnow.com/jobs/" + id, "https://www.arbeitnow.com/jobs/" + id, null, null, null, null, Instant.now(), null)));
    }

    private SearchConfiguration search(UUID user, String keywords, int minScore) {
        SearchConfiguration s = new SearchConfiguration();
        s.setUserId(user);
        s.setName("Java in Berlin");
        s.setKeywords(keywords);
        s.setMinScore(minScore);
        return s;
    }

    private String userId(JsonNode registered) {
        JsonNode id = registered.path("id");
        return id.isMissingNode() ? registered.path("user").path("id").asString() : id.asString();
    }

    @Test
    void alertWithoutCvListsNewOffersThatFitTheSearch() throws Exception {
        MockHttpSession[] s = new MockHttpSession[1];
        UUID user = UUID.fromString(userId(register(s)));
        Instant started = Instant.now().minusSeconds(5);
        ingestJob("n-1", "Java Developer", "Requirements\n- Java\n- Spring Boot");
        ingestJob("n-2", "Pastry Chef", "Bake bread");

        assertThat(alerts.afterAutomaticRun(search(user, "java", 60), started)).isEqualTo(1);

        mvc.perform(get("/api/notifications").session(s[0]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("NEW_MATCHES"))
                .andExpect(jsonPath("$[0].code").value("alert.newOffers"))
                .andExpect(jsonPath("$[0].readAt").doesNotExist());
        mvc.perform(get("/api/notifications/unread-count").session(s[0])).andExpect(jsonPath("$.unread").value(1));

        String id = json.readTree(mvc.perform(get("/api/notifications").session(s[0])).andReturn().getResponse().getContentAsString()).get(0).path("id").asString();
        mvc.perform(post("/api/notifications/" + id + "/read").session(s[0]).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/notifications/unread-count").session(s[0])).andExpect(jsonPath("$.unread").value(0));
        mvc.perform(delete("/api/notifications/" + id).session(s[0]).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/notifications").session(s[0])).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void nothingIsSentWhenNothingNewFitsTheSearch() throws Exception {
        MockHttpSession[] s = new MockHttpSession[1];
        UUID user = UUID.fromString(userId(register(s)));
        Instant started = Instant.now().minusSeconds(5);
        ingestJob("n-3", "Pastry Chef", "Bake bread");

        assertThat(alerts.afterAutomaticRun(search(user, "java", 60), started)).isZero();
        mvc.perform(get("/api/notifications").session(s[0])).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void notificationsBelongToTheirOwner() throws Exception {
        MockHttpSession[] a = new MockHttpSession[1];
        MockHttpSession[] b = new MockHttpSession[1];
        UUID owner = UUID.fromString(userId(register(a)));
        register(b);
        Instant started = Instant.now().minusSeconds(5);
        ingestJob("n-4", "Java Developer", "Requirements\n- Java");
        alerts.afterAutomaticRun(search(owner, "java", 0), started);

        String id = json.readTree(mvc.perform(get("/api/notifications").session(a[0])).andReturn().getResponse().getContentAsString()).get(0).path("id").asString();
        mvc.perform(get("/api/notifications").session(b[0])).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/notifications/" + id + "/read").session(b[0]).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/notifications/" + id).session(b[0]).with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void dueRemindersAndNearInterviewsAreNotifiedOnce() throws Exception {
        dispatcher.dispatchDue(Instant.now()); // other tests share the database; clear what they left due
        MockHttpSession[] s = new MockHttpSession[1];
        register(s);
        ingestJob("n-5", "Java Developer", "Requirements\n- Java");
        String job = offers.findBySourceKeyAndExternalIdAndOwnerUserId("arbeitnow", "n-5", null).orElseThrow().getId().toString();
        MvcResult created = mvc.perform(post("/api/applications").session(s[0]).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobId\":\"" + job + "\"}")).andExpect(status().isCreated()).andReturn();
        String app = json.readTree(created.getResponse().getContentAsString()).path("summary").path("id").asString();
        String base = "/api/applications/" + app;
        mvc.perform(post(base + "/reminders").session(s[0]).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"dueAt\":\"" + Instant.now().minus(Duration.ofHours(1)) + "\",\"text\":\"Chase the recruiter\"}")).andExpect(status().isCreated());
        mvc.perform(post(base + "/interviews").session(s[0]).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"PHONE\",\"scheduledAt\":\"" + Instant.now().plus(Duration.ofHours(5)) + "\",\"durationMinutes\":30}")).andExpect(status().isCreated());

        assertThat(dispatcher.dispatchDue(Instant.now())).isEqualTo(2);
        assertThat(dispatcher.dispatchDue(Instant.now())).isZero();

        String body = mvc.perform(get("/api/notifications").session(s[0])).andExpect(jsonPath("$.length()").value(2)).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("reminder.due").contains("interview.soon").contains("/app/applications/" + app);
        assertThat(body).as("the private reminder text is not copied into the notification").doesNotContain("Chase the recruiter");
    }
}
