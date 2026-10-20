package ai.jobmatcher.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;

import ai.jobmatcher.cv.SkillCatalog;
import ai.jobmatcher.matching.MatchReport.LearningItem;

import org.junit.jupiter.api.Test;

class LearningPlanTest {

    private final LearningCatalog catalog = new LearningCatalog();

    @Test
    void everyCatalogueSkillIsAKnownSkillWithHttpsLinks() {
        SkillCatalog skills = new SkillCatalog();
        for (String s : catalog.skills()) {
            assertThat(skills.lookup(s)).as("unknown skill in learning-resources.csv: " + s).isNotNull();
            for (LearningCatalog.Resource r : catalog.forSkill(s)) {
                assertThat(r.url()).startsWith("https://");
                assertThat(r.title()).isNotBlank();
            }
        }
        assertThat(catalog.forSkill("JAVA".toLowerCase(Locale.ROOT))).isNotEmpty();
    }

    @Test
    void aSkillWithoutAnEntryHasNoResourcesAndNothingIsInvented() {
        assertThat(catalog.forSkill("Underwater Basket Weaving")).isEmpty();
        assertThat(catalog.forSkill(null)).isEmpty();
    }

    @Test
    void skillsRequiredByMostJobsComeFirst() {
        List<LearningItem> gaps = List.of(
                new LearningItem("Docker", "REQUIRED", 2), new LearningItem("Kubernetes", "PREFERRED", 1),
                new LearningItem("Docker", "REQUIRED", 1), new LearningItem("Kafka", "REQUIRED", 1),
                new LearningItem("Kubernetes", "PREFERRED", 3), new LearningItem("Kubernetes", "PREFERRED", 1));
        List<LearningPlanService.Item> items = LearningPlanService.rank(gaps, catalog);
        assertThat(items).extracting(LearningPlanService.Item::skill).containsExactly("Docker", "Kafka", "Kubernetes");
        assertThat(items.get(0).jobs()).isEqualTo(2);
        assertThat(items.get(0).requiredIn()).isEqualTo(2);
        assertThat(items.get(2).preferredIn()).isEqualTo(3);
        assertThat(items.get(0).resources()).isNotEmpty();
    }

    @Test
    void anEmptyGapListGivesAnEmptyPlan() {
        assertThat(LearningPlanService.rank(List.of(), catalog)).isEmpty();
    }
}
