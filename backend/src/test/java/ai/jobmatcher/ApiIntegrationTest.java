package ai.jobmatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** End-to-end API behaviour against a real PostgreSQL (Testcontainers) with the mock AI provider. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ApiIntegrationTest {

    private static final String CV_TEXT = """
            Jane Doe
            jane.doe@example.org

            Profile
            Backend engineer focused on Java services and PostgreSQL databases for logistics teams.

            Experience
            Java Developer - Northwind Labs
            Jan 2020 - Present
            - Built REST APIs with Spring Boot used by 12 teams
            - Cut report time by 40% by tuning PostgreSQL queries

            Skills
            Java, Spring Boot, PostgreSQL, Docker
            """;

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;

    private MockHttpSession register(String email) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"correct horse battery","displayName":"Tester","preferredLanguage":"en","consent":true}
                """.formatted(email))).andExpect(status().isCreated()).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.org";
    }

    private JsonNode createTextCv(MockHttpSession s) throws Exception {
        MvcResult r = mvc.perform(post("/api/cvs/text").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("label", "Main CV", "text", CV_TEXT))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void requiresAuthenticationForProtectedEndpoints() throws Exception {
        mvc.perform(get("/api/cvs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/profile")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/status")).andExpect(status().isOk()).andExpect(jsonPath("$.ai.provider").value("mock"));
    }

    @Test
    void registrationValidatesInputAndRejectsDuplicates() throws Exception {
        String email = uniqueEmail();
        register(email);

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"correct horse battery","displayName":"Again","consent":true}
                """.formatted(email))).andExpect(status().isConflict());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"not-an-email","password":"short","displayName":"","consent":false}
                """)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.password").exists())
                .andExpect(jsonPath("$.fields.consent").exists());
    }

    @Test
    void loginRejectsWrongPasswordWithGenericMessage() throws Exception {
        String email = uniqueEmail();
        register(email);

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"wrong wrong wrong\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value("Invalid email or password."));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"correct horse battery\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void writesWithoutCsrfTokenAreRejected() throws Exception {
        MockHttpSession s = register(uniqueEmail());

        mvc.perform(post("/api/cvs/text").session(s).contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"x\",\"text\":\"" + "a".repeat(80) + "\"}")).andExpect(status().isForbidden());
    }

    @Test
    void cvLifecycleParseReviewAnalyseExportDelete() throws Exception {
        MockHttpSession s = register(uniqueEmail());

        JsonNode created = createTextCv(s);
        String id = created.path("summary").path("id").asString();
        assertThat(created.path("parsed").path("fullName").asString()).isEqualTo("Jane Doe");
        assertThat(created.path("parsed").path("skills").size()).isGreaterThanOrEqualTo(4);
        assertThat(created.path("summary").path("reviewed").asBoolean()).isFalse();

        // The user corrects the extracted data, which marks the CV as reviewed.
        JsonNode parsed = created.path("parsed");
        String corrected = json.writeValueAsString(java.util.Map.of("label", "Main CV (reviewed)", "parsed",
                json.readValue(json.writeValueAsString(parsed).replace("Jane Doe", "Jane Q. Doe"), java.util.Map.class)));
        mvc.perform(put("/api/cvs/" + id + "/profile").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(corrected))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.reviewed").value(true))
                .andExpect(jsonPath("$.parsed.fullName").value("Jane Q. Doe"));

        String badDate = corrected.replace("2020-01", "01/2020");
        mvc.perform(put("/api/cvs/" + id + "/profile").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(badDate))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/cvs/" + id + "/analysis").session(s)).andExpect(status().isNotFound());
        mvc.perform(post("/api/cvs/" + id + "/analysis").session(s).with(csrf())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.overallScore").isNumber()).andExpect(jsonPath("$.aiReview.status").value("ok"));
        mvc.perform(get("/api/cvs/" + id + "/analysis").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.report.skillInventory.database[0]").value("PostgreSQL"));

        mvc.perform(get("/api/cvs/" + id + "/export.md").session(s)).andExpect(status().isOk());

        mvc.perform(delete("/api/cvs/" + id).session(s).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/cvs/" + id).session(s)).andExpect(status().isNotFound());
    }

    @Test
    void usersCannotSeeOrChangeEachOthersCvs() throws Exception {
        MockHttpSession alice = register(uniqueEmail());
        MockHttpSession bob = register(uniqueEmail());
        String aliceCv = createTextCv(alice).path("summary").path("id").asString();

        mvc.perform(get("/api/cvs/" + aliceCv).session(bob)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/cvs/" + aliceCv).session(bob).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/cvs/" + aliceCv + "/analysis").session(bob).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/cvs/" + aliceCv + "/export.md").session(bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/cvs").session(bob)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/cvs/" + aliceCv).session(alice)).andExpect(status().isOk());
    }

    @Test
    void uploadRejectsFilesThatAreNotPdfOrDocx() throws Exception {
        MockHttpSession s = register(uniqueEmail());
        MockMultipartFile fake = new MockMultipartFile("file", "cv.pdf", "application/pdf",
                "MZ this is an executable".getBytes(StandardCharsets.US_ASCII));

        mvc.perform(multipart("/api/cvs").file(fake).session(s).with(csrf())).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Only PDF and DOCX files are accepted."));
    }

    @Test
    void uploadAcceptsDocxAndStoresOnlyEncryptedText() throws Exception {
        MockHttpSession s = register(uniqueEmail());
        byte[] docx = buildDocx(CV_TEXT);

        MvcResult r = mvc.perform(multipart("/api/cvs").file(new MockMultipartFile("file", "../../jane cv.docx",
                "application/octet-stream", docx)).param("label", "Uploaded").session(s).with(csrf()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.summary.sourceType").value("DOCX"))
                .andExpect(jsonPath("$.summary.originalFilename").value("jane cv.docx"))
                .andExpect(jsonPath("$.parsed.email").value("jane.doe@example.org")).andReturn();
        assertThat(r.getResponse().getContentAsString()).contains("Northwind Labs");
    }

    @Test
    void profilePreferencesRoundTripAndValidate() throws Exception {
        MockHttpSession s = register(uniqueEmail());

        mvc.perform(get("/api/profile").session(s)).andExpect(status().isOk()).andExpect(jsonPath("$.desiredTitles.length()").value(0));
        mvc.perform(put("/api/profile").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                {"desiredTitles":["Backend Engineer"],"desiredTechnologies":["Java"],"seniority":"senior","salaryMin":50000,
                 "salaryMax":70000,"salaryCurrency":"EUR","contractTypes":["permanent"],"workModes":["remote","hybrid"],
                 "preferredCountries":["FR"],"preferredLocations":["Lyon"],"willingToRelocate":false,
                 "needsVisaSponsorship":false,"workAuthorization":["FR"],"notifyNewMatches":true,"notifyReminders":false}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.workModes[1]").value("hybrid"))
                .andExpect(jsonPath("$.notifyReminders").value(false));
        mvc.perform(put("/api/profile").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                {"seniority":"wizard","salaryMin":90000,"salaryMax":10000,"willingToRelocate":false,"notifyNewMatches":true,"notifyReminders":true}"""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.seniority").exists());
        mvc.perform(put("/api/profile").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                {"salaryMin":90000,"salaryMax":10000,"willingToRelocate":false,"notifyNewMatches":true,"notifyReminders":true}"""))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void accountExportAndDeletionRemoveEverything() throws Exception {
        String email = uniqueEmail();
        MockHttpSession s = register(email);
        createTextCv(s);

        mvc.perform(get("/api/account/export").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.account.email").value(email)).andExpect(jsonPath("$.cvs.length()").value(1));
        mvc.perform(delete("/api/account").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"wrong wrong wrong\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(delete("/api/account").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"correct horse battery\"}")).andExpect(status().isNoContent());

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"correct horse battery\"}")).andExpect(status().isUnauthorized());
    }

    private static byte[] buildDocx(String text) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (org.apache.poi.xwpf.usermodel.XWPFDocument doc = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            for (String line : text.split("\n")) {
                doc.createParagraph().createRun().setText(line);
            }
            doc.write(out);
        }
        return out.toByteArray();
    }
}
