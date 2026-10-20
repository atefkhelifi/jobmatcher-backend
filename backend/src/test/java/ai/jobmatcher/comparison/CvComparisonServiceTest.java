package ai.jobmatcher.comparison;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import ai.jobmatcher.comparison.CvComparisonService.Difference;
import ai.jobmatcher.cv.ParsedCv;

import org.junit.jupiter.api.Test;

class CvComparisonServiceTest {

    private static ParsedCv cv(String name, String email, List<ParsedCv.Experience> exp, List<ParsedCv.Education> edu) {
        return new ParsedCv(name, email, null, "Backend engineer.", List.of(new ParsedCv.SkillEntry("Java", "language", null)), exp, edu, List.of(),
                List.of(), List.of(), null);
    }

    private static ParsedCv.Experience job(String title, String company, String start, String end, boolean current, String... bullets) {
        return new ParsedCv.Experience(title, company, start, end, current, List.of(bullets));
    }

    @Test
    void identicalVersionsHaveNoDifferences() {
        ParsedCv a = cv("Jane Doe", "jane@example.org", List.of(job("Dev", "Acme", "2019-01", null, true)), List.of());
        assertThat(CvComparisonService.differences(a, a)).isEmpty();
    }

    @Test
    void differentDatesAtTheSameEmployerAreReportedWithBothValues() {
        ParsedCv a = cv("Jane Doe", "jane@example.org", List.of(job("Dev", "Acme", "2019-01", "2022-06", false)), List.of());
        ParsedCv b = cv("Jane Doe", "jane@example.org", List.of(job("Dev", "Acme", "2019-01", "2023-06", false)), List.of());
        List<Difference> d = CvComparisonService.differences(a, b);
        assertThat(d).hasSize(1);
        assertThat(d.get(0).code()).isEqualTo("EXPERIENCE_DATES_DIFFER");
        assertThat(d.get(0).a()).contains("2022-06");
        assertThat(d.get(0).b()).contains("2023-06");
    }

    @Test
    void anEmployerOnlyInOneVersionAndATitleChangeAreReported() {
        ParsedCv a = cv("Jane Doe", null, List.of(job("Senior Dev", "Acme", "2019-01", null, true), job("Dev", "Initech", "2016-01", "2018-12", false)), List.of());
        ParsedCv b = cv("Jane Doe", null, List.of(job("Lead Dev", "Acme", "2019-01", null, true)), List.of());
        List<String> codes = CvComparisonService.differences(a, b).stream().map(Difference::code).toList();
        assertThat(codes).containsExactlyInAnyOrder("EXPERIENCE_ONLY_IN_A", "EXPERIENCE_TITLE_DIFFERS");
    }

    @Test
    void educationAndContactDifferencesAreReportedAndMissingValuesAreNotConflicts() {
        ParsedCv a = cv("Jane Doe", "jane@example.org", List.of(), List.of(new ParsedCv.Education("MSc Computer Science", "Uni Lyon", "2012", "2014")));
        ParsedCv b = cv("Jane Doe", null, List.of(), List.of(new ParsedCv.Education("BSc Computer Science", "Uni Lyon", "2012", "2014")));
        List<String> codes = CvComparisonService.differences(a, b).stream().map(Difference::code).toList();
        assertThat(codes).containsExactly("EDUCATION_DIFFERS"); // an email missing from one version is not a conflict
    }

    @Test
    void readabilityCountsBulletsNumbersAndLongLines() {
        String longBullet = "Led " + "the migration of many services and ".repeat(8) + "to the cloud";
        ParsedCv p = cv("Jane Doe", null, List.of(job("Dev", "Acme", "2019-01", null, true,
                "Cut response time by 40% across 12 services", "Reviewed pull requests", longBullet)), List.of());
        CvComparisonService.Readability r = CvComparisonService.readability(p);
        assertThat(r.bullets()).isEqualTo(3);
        assertThat(r.quantifiedBulletShare()).isEqualTo(33.3);
        assertThat(r.longBullets()).isEqualTo(1);
        assertThat(r.hasSummary()).isTrue();
        assertThat(r.hasEducation()).isFalse();
        assertThat(r.experienceEntries()).isEqualTo(1);
    }

    @Test
    void anEmptyCvDoesNotDivideByZero() {
        CvComparisonService.Readability r = CvComparisonService.readability(ParsedCv.empty());
        assertThat(r.bullets()).isZero();
        assertThat(r.averageBulletWords()).isZero();
        assertThat(r.quantifiedBulletShare()).isZero();
    }
}
