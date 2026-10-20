package ai.jobmatcher.cv;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class CvParserTest {

    private final CvParser parser = new CvParser(new SkillCatalog());

    @Test
    void extractsContactAndSummaryFromEnglishCv() {
        ParsedCv cv = parser.parse(Fixtures.EN);

        assertThat(cv.fullName()).isEqualTo("Jane Doe");
        assertThat(cv.email()).isEqualTo("jane.doe@example.org");
        assertThat(cv.phone()).contains("6 12 34 56 78");
        assertThat(cv.summary()).startsWith("Backend engineer");
    }

    @Test
    void splitsExperienceEntriesWithDatesAndBullets() {
        ParsedCv cv = parser.parse(Fixtures.EN);

        assertThat(cv.experience()).hasSize(2);
        ParsedCv.Experience first = cv.experience().get(0);
        assertThat(first.title()).isEqualTo("Senior Java Developer");
        assertThat(first.company()).isEqualTo("Northwind Labs");
        assertThat(first.start()).isEqualTo("2021-01");
        assertThat(first.current()).isTrue();
        assertThat(first.end()).isNull();
        assertThat(first.bullets()).hasSize(3);

        ParsedCv.Experience second = cv.experience().get(1);
        assertThat(second.title()).isEqualTo("Java Developer");
        assertThat(second.company()).isEqualTo("Contoso Systems");
        assertThat(second.start()).isEqualTo("2018-03");
        assertThat(second.end()).isEqualTo("2020-12");
        assertThat(second.bullets()).hasSize(2);
    }

    @Test
    void recognisesSkillsWithEvidenceAndIgnoresCommonWords() {
        ParsedCv cv = parser.parse(Fixtures.EN);
        List<String> names = cv.skills().stream().map(ParsedCv.SkillEntry::name).toList();

        assertThat(names).contains("Java", "Spring Boot", "PostgreSQL", "Docker", "Kubernetes", "Angular", "Go",
                "REST", "GitHub Actions", "CI/CD");
        assertThat(cv.skills().stream().filter(s -> s.name().equals("Spring Boot")).findFirst().orElseThrow().evidence())
                .contains("Designed REST APIs");
    }

    @Test
    void doesNotReadLowercaseGoAsTheGoLanguage() {
        ParsedCv cv = parser.parse("""
                Alex Test
                Skills
                Python
                Experience
                Developer - Acme
                2020 - 2022
                - Wanted to go further and express ideas clearly
                """);

        assertThat(cv.skills().stream().map(ParsedCv.SkillEntry::name)).doesNotContain("Go", "Express");
    }

    @Test
    void parsesEducationLanguagesAndCertifications() {
        ParsedCv cv = parser.parse(Fixtures.EN);

        assertThat(cv.education()).hasSize(1);
        assertThat(cv.education().get(0).degree()).contains("Master");
        assertThat(cv.education().get(0).institution()).contains("University of Lyon");
        assertThat(cv.education().get(0).end()).isEqualTo("2016");
        assertThat(cv.languages()).extracting(ParsedCv.LanguageLevel::language).containsExactly("English", "French");
        assertThat(cv.languages().get(0).level()).isEqualTo("C1");
        assertThat(cv.certifications()).containsExactly("AWS Certified Developer");
    }

    @Test
    void computesYearsOfExperienceFromDatedRolesOnly() {
        ParsedCv cv = parser.parse(Fixtures.EN);

        // 2018-03..2020-12 (34 months) + 2021-01..today, so at least 8 years in any run after 2026.
        assertThat(cv.yearsOfExperience()).isGreaterThanOrEqualTo(8.0);
    }

    @Test
    void parsesFrenchCvHeadingsMonthsAndLanguages() {
        ParsedCv cv = parser.parse(Fixtures.FR);

        assertThat(cv.fullName()).isEqualTo("Camille Martin");
        assertThat(cv.summary()).contains("Développeuse full stack");
        assertThat(cv.experience()).hasSize(1);
        assertThat(cv.experience().get(0).start()).isEqualTo("2019-09");
        assertThat(cv.experience().get(0).current()).isTrue();
        assertThat(cv.experience().get(0).company()).isEqualTo("Fabrikam");
        assertThat(cv.education().get(0).institution()).contains("École Polytechnique");
        assertThat(cv.languages()).extracting(ParsedCv.LanguageLevel::language).containsExactly("French", "English");
        assertThat(cv.skills().stream().map(ParsedCv.SkillEntry::name)).contains("Angular", "TypeScript", "Kafka", "Agile");
    }

    @Test
    void keepsUnknownFieldsEmptyInsteadOfInventingThem() {
        ParsedCv cv = parser.parse("Just a line of text without any structure at all, nothing else to see here.");

        assertThat(cv.email()).isNull();
        assertThat(cv.summary()).isNull();
        assertThat(cv.experience()).isEmpty();
        assertThat(cv.education()).isEmpty();
        assertThat(cv.yearsOfExperience()).isNull();
    }

    @Test
    void keepsSkillsListedByTheUserEvenWhenTheyAreNotInTheDictionary() {
        ParsedCv cv = parser.parse("""
                Sam Example
                Skills
                Java, Obscure DSL, Another Tool
                """);

        assertThat(cv.skills().stream().map(ParsedCv.SkillEntry::name)).contains("Java", "Obscure DSL", "Another Tool");
    }

    @Test
    void detectsDocumentLanguage() {
        assertThat(CvParser.detectLanguage(Fixtures.FR)).isEqualTo("fr");
        assertThat(CvParser.detectLanguage(Fixtures.EN)).isEqualTo("en");
        assertThat(CvParser.detectLanguage("12345 !!!")).isNull();
        assertThat(CvParser.detectLanguage("Wir suchen einen Entwickler für unser Team. Sie haben Erfahrung mit Java und arbeiten bei uns im Team.")).isEqualTo("de");
        assertThat(CvParser.detectLanguage("We are looking for a developer to join our team. You will build the platform with us.")).isEqualTo("en");
    }
}
