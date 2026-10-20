package ai.jobmatcher.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class TunisianSourcesTest {

    private static String fixture(String name) throws Exception {
        return new String(new ClassPathResource("fixtures/" + name).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void keejobFeedItemsBecomeOffersWithCompanyPlaceAndDate() throws Exception {
        List<RawJob> jobs = KeejobAdapter.parse(fixture("keejob-feed.xml"));

        assertThat(jobs).hasSize(2); // an item with a foreign link and one with an empty title are skipped
        RawJob first = jobs.get(0);
        assertThat(first.externalId()).isEqualTo("248226");
        assertThat(first.title()).isEqualTo("COMPTABLE");
        assertThat(first.company()).isEqualTo("CABINET TRABELSI SAMER");
        assertThat(first.location()).isEqualTo("Tunis");
        assertThat(first.country()).isEqualTo("TN");
        assertThat(first.url()).isEqualTo("https://www.keejob.com/offres-emploi/248226/comptable/");
        assertThat(first.description()).contains("comptable").doesNotContain("<").doesNotContain("Entreptrise");
        assertThat(first.postedAt()).isEqualTo(Instant.parse("2026-10-19T23:00:00Z"));
        assertThat(first.salaryMin()).isNull(); // the feed carries no salary: stays unknown
        assertThat(jobs.get(1).company()).isEqualTo("ACME TN");
        assertThat(jobs.get(1).location()).isEqualTo("Sousse");
    }

    @Test
    void tanitjobsListYieldsEachJobPageOnceAndNothingElse() {
        String html = """
                <a href="https://www.tanitjobs.com/job/2059118/enseignant-e-à-temps-partiel/">A</a>
                <a href="/job/2059118/enseignant-e-à-temps-partiel/">same job again</a>
                <a href="/job/2059000/developpeur/?backPage=1&searchID=7">B</a>
                <a href="/resume/12/cv/">a CV page</a>
                <a href="https://other.example/job/5/x/">another site</a>
                <a href="/companies/">companies</a>
                """;
        List<String> urls = TanitjobsAdapter.jobLinks(html, URI.create("https://www.tanitjobs.com/jobs/"));

        assertThat(urls).hasSize(2);
        assertThat(urls.get(0)).startsWith("https://www.tanitjobs.com/job/2059118/");
        assertThat(urls.get(1)).isEqualTo("https://www.tanitjobs.com/job/2059000/developpeur/"); // tracking parameters dropped
    }

    @Test
    void tanitjobsIsOffUnlessSwitchedOnAndSaysWhy() {
        TanitjobsAdapter off = new TanitjobsAdapter(null, null, false, TanitjobsAdapter.BASE, 25);
        assertThat(off.enabled()).isFalse();
        assertThat(off.disabledReason()).contains("APP_JOBS_TANITJOBS_ENABLED").contains("non-commercial");
        assertThat(new TanitjobsAdapter(null, null, true, TanitjobsAdapter.BASE, 25).enabled()).isTrue();
    }
}
