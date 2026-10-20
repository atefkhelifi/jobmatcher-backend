package ai.jobmatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ai.jobmatcher.jobs.JobOfferRepository;
import ai.jobmatcher.jobs.OfferIngestService;
import ai.jobmatcher.jobs.RawJob;
import ai.jobmatcher.jobs.RemoteMode;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
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

/** The application tracker end to end on a real PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ApplicationsIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JobOfferRepository offers;
    @Autowired OfferIngestService ingest;
    @Autowired JdbcTemplate jdbc;

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

    private String jobA;
    private String jobCopy;

    @BeforeEach
    void catalog() {
        offers.deleteAll();
        Instant deadline = Instant.now().plus(Duration.ofDays(20));
        ingest.ingest("arbeitnow", null, List.of(new RawJob("a-1", "Java Developer (m/f/d)", "Acme GmbH", "Berlin", "DE", RemoteMode.UNKNOWN,
                "Requirements\n- Java\n- Spring Boot", "https://www.arbeitnow.com/jobs/a-1", "https://www.arbeitnow.com/jobs/a-1/apply", null, null, null, null,
                Instant.now(), deadline)));
        // The same role posted on another source: a different offer id, the same role.
        ingest.ingest("greenhouse", null, List.of(new RawJob("acme:77", "Java Developer", "ACME GmbH", "Berlin, Germany", "DE", RemoteMode.UNKNOWN,
                "Requirements\n- Java", "https://boards.greenhouse.io/acme/jobs/77", "https://boards.greenhouse.io/acme/jobs/77", null, null, null, null,
                Instant.now(), null)));
        jobA = offers.findBySourceKeyAndExternalIdAndOwnerUserId("arbeitnow", "a-1", null).orElseThrow().getId().toString();
        jobCopy = offers.findBySourceKeyAndExternalIdAndOwnerUserId("greenhouse", "acme:77", null).orElseThrow().getId().toString();
    }

    private MockHttpSession register() throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"track-%s@example.org","password":"correct horse battery","displayName":"Jane Doe","consent":true}
                """.formatted(UUID.randomUUID()))).andExpect(status().isCreated()).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private MvcResult send(MockHttpSession s, String url, String body) throws Exception {
        return mvc.perform(post(url).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private String track(MockHttpSession s, String job) throws Exception {
        MvcResult r = send(s, "/api/applications", "{\"jobId\":\"" + job + "\"}");
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return body(r).path("summary").path("id").asString();
    }

    private void move(MockHttpSession s, String id, String status, int expected) throws Exception {
        mvc.perform(post("/api/applications/" + id + "/status").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\"}")).andExpect(status().is(expected));
    }

    // ---- create and duplicates ----

    @Test
    void savingAnOfferCopiesItsDetailsAndStartsATimeline() throws Exception {
        MockHttpSession s = register();

        mvc.perform(post("/api/applications").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"jobId\":\"" + jobA + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.summary.status").value("TO_REVIEW"))
                .andExpect(jsonPath("$.summary.title").value("Java Developer (m/f/d)")).andExpect(jsonPath("$.summary.company").value("Acme GmbH"))
                .andExpect(jsonPath("$.summary.jobUrl").value("https://www.arbeitnow.com/jobs/a-1/apply"))
                .andExpect(jsonPath("$.summary.deadlineAt").isNotEmpty()).andExpect(jsonPath("$.summary.appliedAt").doesNotExist())
                .andExpect(jsonPath("$.timeline.length()").value(1)).andExpect(jsonPath("$.timeline[0].type").value("CREATED"))
                .andExpect(jsonPath("$.allowedNext", Matchers.hasItems("SHORTLISTED", "PREPARING", "APPLIED", "WITHDRAWN")));
        mvc.perform(get("/api/applications").session(s)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void trackingTheSameRoleTwiceNeedsConfirmationEvenWhenPostedElsewhere() throws Exception {
        MockHttpSession s = register();
        String first = track(s, jobA);

        // Same offer again
        mvc.perform(post("/api/applications").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"jobId\":\"" + jobA + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("duplicate-application"))
                .andExpect(jsonPath("$.details.length()").value(1)).andExpect(jsonPath("$.details[0].id").value(first))
                .andExpect(jsonPath("$.details[0].sameOffer").value(true));
        // Same role on another source
        mvc.perform(post("/api/applications").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"jobId\":\"" + jobCopy + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.details[0].sameOffer").value(false));
        mvc.perform(get("/api/applications/check").param("jobId", jobCopy).session(s)).andExpect(jsonPath("$.length()").value(1));
        // A manual entry for the same role is caught by its title, company and city
        mvc.perform(post("/api/applications").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"java developer\",\"company\":\"Acme\",\"location\":\"Berlin\"}")).andExpect(status().isConflict());
        mvc.perform(get("/api/applications/check").param("title", "Java Developer").param("company", "ACME GmbH").param("location", "Berlin")
                .session(s)).andExpect(jsonPath("$.length()").value(1));

        // The user may still confirm.
        mvc.perform(post("/api/applications").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobId\":\"" + jobA + "\",\"confirmDuplicate\":true}")).andExpect(status().isCreated());
        mvc.perform(get("/api/applications").session(s)).andExpect(jsonPath("$.length()").value(2));
        // Another user is not warned about someone else's applications.
        mvc.perform(get("/api/applications/check").param("jobId", jobA).session(register())).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void roleFoundElsewhereCanBeTrackedByHand() throws Exception {
        MockHttpSession s = register();

        mvc.perform(post("/api/applications").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Platform Engineer\",\"company\":\"Initech\",\"jobUrl\":\"https://careers.initech.example/42\",\"status\":\"APPLIED\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.summary.status").value("APPLIED")).andExpect(jsonPath("$.summary.appliedAt").isNotEmpty())
                .andExpect(jsonPath("$.summary.jobId").doesNotExist());
        mvc.perform(post("/api/applications").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"company\":\"No title\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/applications").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"X\",\"jobUrl\":\"javascript:alert(1)\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/applications").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Y\",\"status\":\"OFFER\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/applications").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"jobId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());
    }

    // ---- status ----

    @Test
    void onlyLegalTransitionsAreAcceptedAndEveryMoveIsRecorded() throws Exception {
        MockHttpSession s = register();
        String id = track(s, jobA);

        move(s, id, "OFFER", 409);
        move(s, id, "INTERVIEW", 409);
        move(s, id, "TO_REVIEW", 409); // already there
        move(s, id, "SHORTLISTED", 200);
        move(s, id, "PREPARING", 200);

        JsonNode applied = body(send(s, "/api/applications/" + id + "/status", "{\"status\":\"APPLIED\",\"note\":\"Sent via the careers portal\",\"followUpDays\":7}"));
        assertThat(applied.path("summary").path("status").asString()).isEqualTo("APPLIED");
        assertThat(applied.path("summary").path("appliedAt").asString()).isNotEmpty();
        assertThat(applied.path("reminders").size()).isEqualTo(1);
        assertThat(applied.path("reminders").get(0).path("text").asString()).isEqualTo("Follow up on this application");
        assertThat(applied.path("reminders").get(0).path("overdue").asBoolean()).isFalse();

        move(s, id, "PREPARING", 409);
        move(s, id, "INTERVIEW", 200);
        move(s, id, "ASSESSMENT", 200);
        move(s, id, "OFFER", 200);
        move(s, id, "APPLIED", 409);
        move(s, id, "REJECTED", 200);
        move(s, id, "APPLIED", 409); // final
        move(s, id, "WITHDRAWN", 409);

        JsonNode d = body(mvc.perform(get("/api/applications/" + id).session(s)).andExpect(status().isOk()).andExpect(jsonPath("$.allowedNext.length()").value(0)).andReturn());
        List<String> path = d.path("timeline").valueStream().filter(e -> e.path("type").asString().equals("STATUS"))
                .map(e -> e.path("from").asString() + ">" + e.path("to").asString()).toList();
        assertThat(path).containsExactly("TO_REVIEW>SHORTLISTED", "SHORTLISTED>PREPARING", "PREPARING>APPLIED", "APPLIED>INTERVIEW", "INTERVIEW>ASSESSMENT",
                "ASSESSMENT>OFFER", "OFFER>REJECTED");
        assertThat(d.path("timeline").get(3).path("note").asString()).isEqualTo("Sent via the careers portal");
        mvc.perform(post("/api/applications/" + id + "/status").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"BANANA\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/applications/" + id + "/status").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void filteringByStatusAndTheDashboardSummariseTheBoard() throws Exception {
        MockHttpSession s = register();
        String a = track(s, jobA);
        send(s, "/api/applications", "{\"title\":\"Data Engineer\",\"company\":\"Beta\",\"status\":\"APPLIED\"}");
        move(s, a, "APPLIED", 200);

        mvc.perform(get("/api/applications").param("status", "APPLIED").session(s)).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/applications").param("status", "REJECTED").session(s)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/applications/dashboard").session(s)).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.byStatus.APPLIED").value(2))
                .andExpect(jsonPath("$.byStatus.OFFER").value(0));
    }

    // ---- details ----

    @Test
    void notesContactsInterviewsRemindersAndLinksAreStoredAndShownInTheTimeline() throws Exception {
        MockHttpSession s = register();
        String id = track(s, jobA);
        String base = "/api/applications/" + id;
        String future = Instant.now().plus(Duration.ofDays(3)).toString();

        mvc.perform(post(base + "/notes").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Spoke to the recruiter\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post(base + "/notes").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\" \"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(post(base + "/contacts").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Marie Dupont\",\"role\":\"Talent Acquisition\",\"email\":\"marie@acme-corp.io\",\"phone\":\"+33 1 23 45 67 89\"}")).andExpect(status().isCreated());
        mvc.perform(post(base + "/interviews").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"VIDEO\",\"scheduledAt\":\"" + future + "\",\"durationMinutes\":45,\"location\":\"https://meet.example/xyz\",\"notes\":\"Prepare system design\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post(base + "/reminders").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"dueAt\":\"" + Instant.now().minus(Duration.ofDays(1)) + "\",\"text\":\"Email Marie\"}")).andExpect(status().isCreated());
        mvc.perform(post(base + "/attachments").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"Careers portal\",\"url\":\"https://careers.acme-corp.io/me\"}")).andExpect(status().isCreated());
        mvc.perform(post(base + "/attachments").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"Bad\",\"url\":\"javascript:alert(1)\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(put(base).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Java Developer\",\"company\":\"Acme GmbH\",\"notes\":\"Salary talk after round two\",\"appliedAt\":null}")).andExpect(status().isOk());

        JsonNode d = body(mvc.perform(get(base).session(s)).andExpect(status().isOk()).andReturn());
        assertThat(d.path("notes").asString()).isEqualTo("Salary talk after round two");
        assertThat(d.path("contacts").get(0).path("email").asString()).isEqualTo("marie@acme-corp.io");
        assertThat(d.path("interviews").get(0).path("location").asString()).isEqualTo("https://meet.example/xyz");
        assertThat(d.path("reminders").get(0).path("overdue").asBoolean()).isTrue();
        assertThat(d.path("summary").path("nextInterviewAt").asString()).isNotEmpty();
        assertThat(d.path("summary").path("openReminders").asInt()).isEqualTo(1);
        assertThat(d.path("timeline").valueStream().map(e -> e.path("type").asString()).toList())
                .containsExactly("CREATED", "NOTE", "CONTACT", "INTERVIEW", "REMINDER", "ATTACHMENT");

        // Completing the reminder clears it from the open count.
        String rid = d.path("reminders").get(0).path("id").asString();
        mvc.perform(post(base + "/reminders/" + rid + "/done").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"done\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.openReminders").value(0)).andExpect(jsonPath("$.reminders[0].doneAt").isNotEmpty());

        // Removing items works.
        mvc.perform(delete(base + "/contacts/" + d.path("contacts").get(0).path("id").asString()).session(s).with(csrf())).andExpect(jsonPath("$.contacts.length()").value(0));
        mvc.perform(delete(base + "/interviews/" + d.path("interviews").get(0).path("id").asString()).session(s).with(csrf())).andExpect(jsonPath("$.interviews.length()").value(0));
        mvc.perform(delete(base + "/reminders/" + rid).session(s).with(csrf())).andExpect(jsonPath("$.reminders.length()").value(0));
        mvc.perform(delete(base + "/attachments/" + d.path("attachments").get(0).path("id").asString()).session(s).with(csrf())).andExpect(jsonPath("$.attachments.length()").value(0));
    }

    @Test
    void personalTextIsEncryptedInTheDatabase() throws Exception {
        MockHttpSession s = register();
        String id = track(s, jobA);
        String base = "/api/applications/" + id;
        send(s, base + "/notes", "{\"text\":\"Salary expectations 70k\"}");
        send(s, base + "/contacts", "{\"name\":\"Marie Dupont\",\"email\":\"marie@acme-corp.io\"}");
        send(s, base + "/interviews", "{\"kind\":\"ONSITE\",\"scheduledAt\":\"" + Instant.now().plus(Duration.ofDays(2)) + "\",\"location\":\"12 rue Exemple, Lyon\"}");
        send(s, base + "/reminders", "{\"dueAt\":\"" + Instant.now().plus(Duration.ofDays(2)) + "\",\"text\":\"Call back about the offer\"}");
        mvc.perform(put(base).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Java Developer\",\"notes\":\"Private thoughts about the team\"}"));

        UUID uid = UUID.fromString(id);
        String stored = String.join(" ",
                jdbc.queryForList("select notes from job_application where id = ?", String.class, uid).toString(),
                jdbc.queryForList("select note from application_event where application_id = ? and note is not null", String.class, uid).toString(),
                jdbc.queryForList("select name || coalesce(email, '') from application_contact where application_id = ?", String.class, uid).toString(),
                jdbc.queryForList("select coalesce(location, '') from application_interview where application_id = ?", String.class, uid).toString(),
                jdbc.queryForList("select text from application_reminder where application_id = ?", String.class, uid).toString());
        assertThat(stored).isNotBlank().doesNotContain("Salary expectations").doesNotContain("Marie").doesNotContain("marie@acme-corp.io")
                .doesNotContain("rue Exemple").doesNotContain("Call back").doesNotContain("Private thoughts");
    }

    @Test
    void lettersAndEmailsCanBeAttachedButOnlyYourOwn() throws Exception {
        MockHttpSession alice = register();
        MockHttpSession bob = register();
        String cv = body(send(alice, "/api/cvs/text", json.writeValueAsString(Map.of("label", "CV", "text", CV)))).path("summary").path("id").asString();
        String letterId = body(send(alice, "/api/jobs/" + jobA + "/letters", "{\"cvId\":\"" + cv + "\",\"useAi\":false}")).path("id").asString();
        String id = track(alice, jobA);

        mvc.perform(post("/api/applications/" + id + "/attachments").session(alice).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentId\":\"" + letterId + "\"}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachments[0].kind").value("DOCUMENT")).andExpect(jsonPath("$.attachments[0].label").value("Cover letter v1"))
                .andExpect(jsonPath("$.attachments[0].documentId").value(letterId));
        String bobsApp = track(bob, jobA);
        mvc.perform(post("/api/applications/" + bobsApp + "/attachments").session(bob).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentId\":\"" + letterId + "\"}")).andExpect(status().isNotFound());
        mvc.perform(put("/api/applications/" + bobsApp).session(bob).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Java Developer\",\"cvId\":\"" + cv + "\"}")).andExpect(status().isNotFound());
    }

    // ---- calendar and reminders ----

    @Test
    void calendarExportListsInterviewsOpenRemindersAndDeadlines() throws Exception {
        MockHttpSession s = register();
        String id = track(s, jobA);
        send(s, "/api/applications/" + id + "/interviews", "{\"kind\":\"PHONE\",\"scheduledAt\":\"2027-03-02T09:00:00Z\",\"durationMinutes\":30,\"location\":\"Call, ask for Marie\"}");
        send(s, "/api/applications/" + id + "/reminders", "{\"dueAt\":\"2027-03-05T08:00:00Z\",\"text\":\"Send thanks; follow up\"}");
        String done = body(send(s, "/api/applications/" + id + "/reminders", "{\"dueAt\":\"2027-03-06T08:00:00Z\",\"text\":\"Already handled\"}")).path("reminders").get(1).path("id").asString();
        send(s, "/api/applications/" + id + "/reminders/" + done + "/done", "{\"done\":true}");

        String all = mvc.perform(get("/api/applications/calendar.ics").session(s)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/calendar")).andReturn().getResponse().getContentAsString();
        assertThat(all).startsWith("BEGIN:VCALENDAR\r\n").contains("SUMMARY:Interview (phone): Java Developer (m/f/d) - Acme GmbH")
                .contains("DTSTART:20270302T090000Z").contains("DTEND:20270302T093000Z").contains("LOCATION:Call\\, ask for Marie")
                .contains("SUMMARY:Send thanks\\; follow up (Java Developer (m/f/d) - Acme GmbH)").doesNotContain("Already handled")
                .contains("SUMMARY:Application deadline: Java Developer");
        assertThat(all.split("BEGIN:VEVENT", -1).length - 1).isEqualTo(3);

        // After applying, the deadline no longer belongs in the calendar.
        move(s, id, "APPLIED", 200);
        assertThat(mvc.perform(get("/api/applications/calendar.ics").param("applicationId", id).session(s)).andReturn().getResponse().getContentAsString())
                .doesNotContain("Application deadline");
        mvc.perform(get("/api/applications/calendar.ics").param("applicationId", UUID.randomUUID().toString()).session(s)).andExpect(status().isNotFound());
        mvc.perform(get("/api/applications/calendar.ics").session(register())).andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString("BEGIN:VEVENT"))));
        mvc.perform(get("/api/applications/calendar.ics")).andExpect(status().isUnauthorized());
    }

    @Test
    void dashboardShowsOverdueRemindersAndUpcomingInterviews() throws Exception {
        MockHttpSession s = register();
        String id = track(s, jobA);
        send(s, "/api/applications/" + id + "/reminders", "{\"dueAt\":\"" + Instant.now().minus(Duration.ofDays(2)) + "\",\"text\":\"Chase the recruiter\"}");
        send(s, "/api/applications/" + id + "/interviews", "{\"kind\":\"ONSITE\",\"scheduledAt\":\"" + Instant.now().plus(Duration.ofDays(4)) + "\",\"location\":\"Berlin office\"}");

        mvc.perform(get("/api/applications/dashboard").session(s)).andExpect(jsonPath("$.dueReminders[0].text").value("Chase the recruiter"))
                .andExpect(jsonPath("$.dueReminders[0].overdue").value(true)).andExpect(jsonPath("$.dueReminders[0].applicationTitle").value("Java Developer (m/f/d)"))
                .andExpect(jsonPath("$.upcomingInterviews[0].kind").value("ONSITE")).andExpect(jsonPath("$.upcomingInterviews[0].location").value("Berlin office"));
    }

    // ---- ownership and lifecycle ----

    @Test
    void applicationsBelongToTheirOwner() throws Exception {
        MockHttpSession alice = register();
        MockHttpSession bob = register();
        String id = track(alice, jobA);
        String base = "/api/applications/" + id;

        mvc.perform(get(base).session(bob)).andExpect(status().isNotFound());
        mvc.perform(put(base).session(bob).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}")).andExpect(status().isNotFound());
        mvc.perform(delete(base).session(bob).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post(base + "/status").session(bob).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"APPLIED\"}")).andExpect(status().isNotFound());
        mvc.perform(post(base + "/notes").session(bob).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"hello\"}")).andExpect(status().isNotFound());
        mvc.perform(post(base + "/contacts").session(bob).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}")).andExpect(status().isNotFound());
        mvc.perform(post(base + "/interviews").session(bob).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scheduledAt\":\"2027-01-01T10:00:00Z\"}")).andExpect(status().isNotFound());
        mvc.perform(post(base + "/reminders").session(bob).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"dueAt\":\"2027-01-01T10:00:00Z\",\"text\":\"x\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/applications").session(bob)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/applications/dashboard").session(bob)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(base).session(alice)).andExpect(status().isOk());
        mvc.perform(get("/api/applications")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/applications").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void deletingAnApplicationRemovesItsRecordsAndLosingTheOfferKeepsTheApplication() throws Exception {
        MockHttpSession s = register();
        String id = track(s, jobA);
        send(s, "/api/applications/" + id + "/notes", "{\"text\":\"n\"}");
        send(s, "/api/applications/" + id + "/contacts", "{\"name\":\"Marie\"}");
        UUID uid = UUID.fromString(id);

        // The offer disappears from the catalogue; the tracked application and its history stay.
        offers.deleteAll();
        mvc.perform(get("/api/applications/" + id).session(s)).andExpect(status().isOk()).andExpect(jsonPath("$.summary.title").value("Java Developer (m/f/d)"))
                .andExpect(jsonPath("$.summary.jobId").doesNotExist()).andExpect(jsonPath("$.timeline.length()").value(3));

        mvc.perform(delete("/api/applications/" + id).session(s).with(csrf())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from application_event where application_id = ?", Long.class, uid)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from application_contact where application_id = ?", Long.class, uid)).isZero();
        mvc.perform(get("/api/applications/" + id).session(s)).andExpect(status().isNotFound());
    }

    @Test
    void deletingTheAccountRemovesTheTracker() throws Exception {
        MockHttpSession s = register();
        String id = track(s, jobA);
        String email = jdbc.queryForObject("select email from app_user u join job_application a on a.user_id = u.id where a.id = ?", String.class, UUID.fromString(id));

        mvc.perform(delete("/api/account").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"correct horse battery\"}"))
                .andExpect(status().isNoContent());

        assertThat(email).startsWith("track-");
        assertThat(jdbc.queryForObject("select count(*) from job_application where id = ?", Long.class, UUID.fromString(id))).isZero();
    }
}
