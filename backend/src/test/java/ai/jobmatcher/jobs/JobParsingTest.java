package ai.jobmatcher.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import ai.jobmatcher.fetch.SafeFetcher;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class JobParsingTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/fixtures", name), StandardCharsets.UTF_8);
    }

    @Test
    void htmlTextDecodesDoubleEncodedHtmlAndDropsScripts() {
        String text = HtmlText.toText("&lt;p&gt;&lt;strong&gt;About&lt;/strong&gt;&lt;/p&gt;&lt;ul&gt;&lt;li&gt;One&lt;/li&gt;&lt;li&gt;Two &amp;amp; three&lt;/li&gt;&lt;/ul&gt;&lt;script&gt;alert(1)&lt;/script&gt;");

        assertThat(text).contains("About").contains("• One").contains("• Two & three");
        assertThat(text).doesNotContain("<").doesNotContain("alert").doesNotContain("script");
    }

    @Test
    void htmlTextKeepsPlainTextAndHandlesEmpty() {
        assertThat(HtmlText.toText("Plain text, no markup.")).isEqualTo("Plain text, no markup.");
        assertThat(HtmlText.toText(null)).isEmpty();
        assertThat(HtmlText.toText("<p>Hello<br>world</p>")).isEqualTo("Hello\nworld");
    }

    @Test
    void arbeitnowFixtureMapsToNormalisedJobs() throws IOException {
        ArbeitnowAdapter a = new ArbeitnowAdapter(null, json, "http://unused");
        var data = json.readTree(fixture("arbeitnow.json")).path("data");

        RawJob java = a.map(data.get(0));
        assertThat(java.externalId()).isEqualTo("java-developer-berlin-1001");
        assertThat(java.title()).isEqualTo("Java Developer (m/w/d)");
        assertThat(java.company()).isEqualTo("Acme GmbH");
        assertThat(java.remote()).isEqualTo(RemoteMode.UNKNOWN); // "not remote" is not the same as "on site"
        assertThat(java.description()).contains("Spring Boot").contains("PostgreSQL & Docker").doesNotContain("alert");
        assertThat(java.salaryMin()).isNull();
        assertThat(java.postedAt()).isEqualTo(Instant.ofEpochSecond(1760000000));

        assertThat(a.map(data.get(1)).remote()).isEqualTo(RemoteMode.REMOTE);
        assertThat(a.map(data.get(3))).isNull(); // missing slug
    }

    @Test
    void greenhouseMappingUsesBoardAsFallbackCompany() {
        GreenhouseAdapter g = new GreenhouseAdapter(null, json, List.of("acme"), "http://unused");
        var node = json.readTree("""
                {"id": 77, "title": "Staff Engineer", "absolute_url": "https://boards.greenhouse.io/acme/jobs/77",
                 "location": {"name": "Remote - Europe"}, "content": "&lt;p&gt;Lead platform work.&lt;/p&gt;",
                 "first_published": "2026-09-01T10:00:00-04:00"}""");

        RawJob job = g.map("acme", node);

        assertThat(job.externalId()).isEqualTo("acme:77");
        assertThat(job.company()).isEqualTo("acme");
        assertThat(job.remote()).isEqualTo(RemoteMode.REMOTE);
        assertThat(job.description()).isEqualTo("Lead platform work.");
        assertThat(job.postedAt()).isEqualTo(Instant.parse("2026-09-01T14:00:00Z"));
    }

    @Test
    void greenhouseIsDisabledWithoutBoardsAndIgnoresMaliciousBoardNames() {
        assertThat(new GreenhouseAdapter(null, json, List.of(), "x").enabled()).isFalse();
        assertThat(new GreenhouseAdapter(null, json, List.of("../etc", "ok-board", "a b"), "x").enabled()).isTrue();
    }

    @Test
    void franceTravailMappingAndDisabledState() {
        SafeFetcher unused = new SafeFetcher(false, 1000, Duration.ofSeconds(1), Duration.ZERO);
        FranceTravailAdapter ft = new FranceTravailAdapter(unused, json, "", "", "http://t", "http://a");
        assertThat(ft.enabled()).isFalse();
        assertThat(ft.disabledReason()).contains("francetravail.io");

        var node = json.readTree("""
                {"id": "123ABCD", "intitule": "Développeur Java", "description": "Concevoir des API REST.",
                 "dateCreation": "2026-10-02T08:30:00.000Z", "lieuTravail": {"libelle": "69 - LYON 03"},
                 "entreprise": {"nom": "Acme SAS"}, "typeContrat": "CDI",
                 "origineOffre": {"urlOrigine": "https://candidat.francetravail.fr/offres/recherche/detail/123ABCD"}}""");
        RawJob job = new FranceTravailAdapter(unused, json, "id", "secret", "http://t", "http://a").map(node);

        assertThat(job.title()).isEqualTo("Développeur Java");
        assertThat(job.location()).isEqualTo("69 - LYON 03");
        assertThat(job.country()).isEqualTo("FR");
        assertThat(job.employmentType()).isEqualTo("CDI");
        assertThat(job.salaryMin()).isNull();
        assertThat(job.postedAt()).isEqualTo(Instant.parse("2026-10-02T08:30:00Z"));
    }

    @Test
    void jsonLdParserFindsJobPostingInGraphAndSkipsBrokenBlocks() throws IOException {
        JobPostingParser p = new JobPostingParser(json);

        RawJob job = p.parse(fixture("jobposting.html"), URI.create("https://careers.initech.example/jobs/4711")).orElseThrow();

        assertThat(job.externalId()).isEqualTo("REQ-4711");
        assertThat(job.title()).isEqualTo("Backend Engineer");
        assertThat(job.company()).isEqualTo("Initech SAS");
        assertThat(job.location()).isEqualTo("Lyon, ARA");
        assertThat(job.country()).isEqualTo("FR");
        assertThat(job.remote()).isEqualTo(RemoteMode.REMOTE);
        assertThat(job.salaryMin()).isEqualTo(50000);
        assertThat(job.salaryMax()).isEqualTo(65000);
        assertThat(job.salaryCurrency()).isEqualTo("EUR");
        assertThat(job.employmentType()).isEqualTo("full_time");
        assertThat(job.description()).contains("Build services in Java.").contains("Spring Boot");
        assertThat(job.postedAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(job.validThrough()).isEqualTo(Instant.parse("2026-12-31T23:59:59Z"));
        assertThat(job.url()).isEqualTo("https://careers.initech.example/jobs/4711");
        assertThat(job.applyUrl()).isEqualTo("https://careers.initech.example/apply/4711");
    }

    @Test
    void jsonLdParserDropsSalaryThatIsNotYearly() {
        String html = """
                <script type="application/ld+json">{"@type":"JobPosting","title":"Dev","description":"Do things well.",
                "baseSalary":{"currency":"EUR","value":{"minValue":30,"maxValue":40,"unitText":"HOUR"}}}</script>""";

        RawJob job = new JobPostingParser(json).parse(html, URI.create("https://x.example/j")).orElseThrow();

        assertThat(job.salaryMin()).isNull();
        assertThat(job.salaryCurrency()).isNull();
        assertThat(job.externalId()).hasSize(40); // derived from the URL when the page has no identifier
    }

    @Test
    void jsonLdParserReturnsEmptyWhenNoPosting() {
        JobPostingParser p = new JobPostingParser(json);

        assertThat(p.parse("<html><body>No data</body></html>", URI.create("https://x.example/"))).isEmpty();
        assertThat(p.parse("<script type=\"application/ld+json\">{\"@type\":\"Organization\"}</script>", URI.create("https://x.example/"))).isEmpty();
        assertThat(p.parse("<script type=\"application/ld+json\">{\"@type\":\"JobPosting\",\"title\":\"No description\"}</script>", URI.create("https://x.example/"))).isEmpty();
    }

    @Test
    void fingerprintTreatsSameRoleAcrossSourcesAsEqual() {
        String a = Fingerprint.of("Java Developer (m/w/d)", "Acme GmbH", "Berlin");
        String b = Fingerprint.of("java developer", "ACME GmbH", "Berlin, Germany");
        String c = Fingerprint.of("Développeur Java", "Acme", "Lyon");
        String d = Fingerprint.of("Développeur   JAVA", "ACME", "lyon");

        assertThat(a).isEqualTo(b);
        assertThat(c).isEqualTo(d);
        assertThat(a).isNotEqualTo(c);
        assertThat(Fingerprint.of("Java Developer", "Acme", "Berlin")).isNotEqualTo(Fingerprint.of("Java Developer", "Acme", "Hamburg"));
    }

    @Test
    void fingerprintIgnoresLegalFormsOfTheCompanyButNotDifferentCompanies() {
        String base = Fingerprint.of("Java Developer", "Acme", "Berlin");

        assertThat(Fingerprint.of("Java Developer", "Acme GmbH", "Berlin")).isEqualTo(base);
        assertThat(Fingerprint.of("Java Developer", "ACME Inc.", "Berlin")).isEqualTo(base);
        assertThat(Fingerprint.of("Java Developer", "Acme S.A.S.", "Berlin")).isEqualTo(base);
        assertThat(Fingerprint.of("Java Developer", "Acme Systems", "Berlin")).isNotEqualTo(base);
        assertThat(Fingerprint.of("Java Developer", "Beta GmbH", "Berlin")).isNotEqualTo(base);
    }

    @Test
    void offlineExpiryRules() {
        Instant now = Instant.parse("2026-10-20T00:00:00Z");
        JobOffer open = new JobOffer();
        open.setLastSeenAt(now.minus(Duration.ofDays(2)));
        assertThat(OfferVerifier.offlineReason(open, now)).isNull();

        JobOffer deadline = new JobOffer();
        deadline.setLastSeenAt(now);
        deadline.setValidThrough(now.minus(Duration.ofHours(1)));
        assertThat(OfferVerifier.offlineReason(deadline, now)).isEqualTo("deadline passed");

        JobOffer future = new JobOffer();
        future.setLastSeenAt(now);
        future.setValidThrough(now.plus(Duration.ofDays(5)));
        assertThat(OfferVerifier.offlineReason(future, now)).isNull();

        JobOffer stale = new JobOffer();
        stale.setLastSeenAt(now.minus(Duration.ofDays(31)));
        assertThat(OfferVerifier.offlineReason(stale, now)).contains("not seen");
    }

    @Test
    void searchQueryMatchesAllKeywordsAndLocationCaseInsensitively() {
        SearchQuery q = SearchQuery.of("java  SPRING", "berlin", false);

        assertThat(q.matchesKeywords("Senior Java dev, Spring Boot")).isTrue();
        assertThat(q.matchesKeywords("Senior Java dev")).isFalse();
        assertThat(q.matchesLocation("Berlin, DE")).isTrue();
        assertThat(q.matchesLocation(null)).isFalse();
        assertThat(SearchQuery.of("", "", false).matchesLocation(null)).isTrue();
    }
}
