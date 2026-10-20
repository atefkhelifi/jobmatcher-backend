package ai.jobmatcher.cv;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class CvAnalyzerTest {

    private final CvParser parser = new CvParser(new SkillCatalog());
    private final CvAnalyzer analyzer = new CvAnalyzer();

    private CvAnalyzer.Report analyze(String text, String lang) {
        return analyzer.analyze(text, parser.parse(text), lang);
    }

    private static List<String> titles(CvAnalyzer.Report r) {
        return r.findings().stream().map(CvAnalyzer.Finding::title).toList();
    }

    @Test
    void flagsWeakDutyPhrasingAndQuotesTheOffendingBullet() {
        CvAnalyzer.Report r = analyze(Fixtures.EN, "en");

        CvAnalyzer.Finding weak = r.findings().stream().filter(f -> f.title().contains("duties")).findFirst().orElseThrow();
        assertThat(weak.evidence()).containsExactly("Responsible for maintaining legacy modules");
        assertThat(weak.suggestion()).doesNotContainPattern("\\d+%");
    }

    @Test
    void suggestionsNeverSupplyInventedFigures() {
        CvAnalyzer.Report r = analyze(Fixtures.EN, "en");

        r.findings().forEach(f -> assertThat(f.suggestion()).doesNotContainPattern("\\b\\d{2,}\\s*%"));
    }

    @Test
    void rankedHighSeverityFirst() {
        CvAnalyzer.Report r = analyze("Some Person\nLooking for a job in software, I like computers a lot and learning new things every day.", "en");

        assertThat(r.findings().get(0).severity()).isEqualTo(CvAnalyzer.Severity.HIGH);
        assertThat(r.findings()).isSortedAccordingTo(java.util.Comparator.comparing(CvAnalyzer.Finding::severity));
        assertThat(titles(r)).contains("No email address found", "No work experience section recognised");
    }

    @Test
    void wellFormedCvScoresHigherThanAnEmptyOne() {
        int good = analyze(Fixtures.EN, "en").overallScore();
        int poor = analyze("Nothing much here at all, only a few words in a short text.", "en").overallScore();

        assertThat(good).isGreaterThan(poor);
        assertThat(good).isBetween(0, 100);
        assertThat(poor).isBetween(0, 100);
    }

    @Test
    void detectsRoleEndingBeforeItStarts() {
        String cv = """
                Test Person
                test@example.org
                Experience
                Developer - Acme
                2022 - 2019
                - Built things
                - Fixed things
                """;

        assertThat(titles(analyze(cv, "en"))).contains("A role ends before it starts");
    }

    @Test
    void reportsGapsBetweenRolesAsLowSeverity() {
        String cv = """
                Test Person
                test@example.org
                Experience
                Developer - Acme
                2015 - 2016
                - Built things
                - Fixed things
                Developer - Globex
                2019 - 2020
                - Built more
                - Fixed more
                """;

        CvAnalyzer.Finding gap = analyze(cv, "en").findings().stream().filter(f -> f.title().contains("Gap")).findFirst().orElseThrow();
        assertThat(gap.severity()).isEqualTo(CvAnalyzer.Severity.LOW);
    }

    @Test
    void suggestsInFrenchForFrenchCvs() {
        CvAnalyzer.Report r = analyze(Fixtures.FR, "fr");

        CvAnalyzer.Finding weak = r.findings().stream().filter(f -> f.title().contains("duties")).findFirst().orElseThrow();
        assertThat(weak.suggestion()).contains("verbe d'action");
    }

    @Test
    void buildsSkillInventoryGroupedByCategory() {
        CvAnalyzer.Report r = analyze(Fixtures.EN, "en");

        assertThat(r.skillInventory()).containsKeys("language", "devops", "database");
        assertThat(r.skillInventory().get("database")).contains("PostgreSQL");
    }
}
