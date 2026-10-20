package ai.jobmatcher.interview;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import ai.jobmatcher.interview.InterviewPrepService.Kind;
import ai.jobmatcher.interview.InterviewPrepService.Prep;
import ai.jobmatcher.interview.InterviewPrepService.Question;
import ai.jobmatcher.learning.LearningCatalog;
import ai.jobmatcher.matching.MatchReport;
import ai.jobmatcher.matching.MatchReport.SkillResult;

import org.junit.jupiter.api.Test;

class InterviewPrepServiceTest {

    private final LearningCatalog catalog = new LearningCatalog();

    private static MatchReport report(SkillResult... skills) {
        return new MatchReport(70, 80, false, List.of(), List.of(skills), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), true);
    }

    private Prep prep(String lang, SkillResult... skills) {
        return InterviewPrepService.build(UUID.randomUUID(), "Java Developer", "Acme", lang, report(skills), catalog);
    }

    @Test
    void aSkillTheCvShowsBecomesAnExperienceQuestionCarryingTheCvQuote() {
        Prep p = prep("en", new SkillResult("Java", "REQUIRED", false, true, "Designed REST APIs with Spring Boot used by 12 teams", 3));
        Question q = p.questions().get(0);
        assertThat(q.kind()).isEqualTo(Kind.EXPERIENCE);
        assertThat(q.evidence()).isEqualTo("Designed REST APIs with Spring Boot used by 12 teams");
        assertThat(q.scaffold()).anyMatch(s -> s.startsWith("Situation")).anyMatch(s -> s.startsWith("Result"));
        assertThat(q.weakEvidence()).isFalse();
    }

    @Test
    void aMissingSkillBecomesAnHonestGapQuestionWithoutInventedEvidence() {
        Prep p = prep("en", new SkillResult("Docker", "REQUIRED", false, false, null, 2));
        Question q = p.questions().get(0);
        assertThat(q.kind()).isEqualTo(Kind.GAP);
        assertThat(q.evidence()).isNull();
        assertThat(q.text()).contains("Docker").contains("does not show");
        assertThat(q.scaffold().get(0)).containsIgnoringCase("plainly");
    }

    @Test
    void anInferredSkillIsMarkedAsWeakEvidence() {
        Prep p = prep("en", new SkillResult("Spring Boot", "REQUIRED", true, true, "Worked with Spring services", 1));
        assertThat(p.questions().get(0).weakEvidence()).isTrue();
    }

    @Test
    void requiredSkillsComeBeforePreferredAndGapsBeforeShownSkillsWithinALevel() {
        Prep p = prep("en", new SkillResult("Redis", "PREFERRED", false, false, null, 1), new SkillResult("Java", "REQUIRED", false, true, "x", 1),
                new SkillResult("Docker", "REQUIRED", false, false, null, 1));
        assertThat(p.questions().stream().limit(3).map(Question::skill)).containsExactly("Docker", "Java", "Redis");
    }

    @Test
    void everyPrepHasBehaviouralMotivationAndQuestionsToAsk() {
        Prep p = prep("en");
        assertThat(p.questions()).extracting(Question::kind).contains(Kind.BEHAVIORAL, Kind.MOTIVATION, Kind.ASK_THEM);
        assertThat(p.questions().stream().filter(q -> q.kind() == Kind.MOTIVATION).findFirst().orElseThrow().text()).contains("Java Developer").contains("Acme");
    }

    @Test
    void frenchPrepIsInFrenchAndRevisionListsLinksOnlyForCataloguedSkills() {
        Prep p = prep("fr", new SkillResult("Docker", "REQUIRED", false, false, null, 1), new SkillResult("Zymurgy", "PREFERRED", false, false, null, 1));
        assertThat(p.questions().get(0).text()).contains("Votre CV ne le montre pas");
        assertThat(p.revision()).hasSize(2);
        assertThat(p.revision().get(0).resources()).isNotEmpty();
        assertThat(p.revision().get(1).resources()).isEmpty();
    }

    @Test
    void theNumberOfSkillQuestionsIsCapped() {
        SkillResult[] many = new SkillResult[12];
        for (int i = 0; i < many.length; i++) {
            many[i] = new SkillResult("Skill" + i, "REQUIRED", false, false, null, 1);
        }
        long skillQs = prep("en", many).questions().stream().filter(q -> q.kind() == Kind.GAP).count();
        assertThat(skillQs).isEqualTo(InterviewPrepService.MAX_SKILL_QUESTIONS);
    }
}
