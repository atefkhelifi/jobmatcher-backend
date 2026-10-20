package ai.jobmatcher.learning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ai.jobmatcher.applications.ApplicationRepository;
import ai.jobmatcher.applications.JobApplication;
import ai.jobmatcher.matching.MatchReport;
import ai.jobmatcher.matching.MatchService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns the gaps found in the user's open applications into a short learning plan. The skills that most of those jobs require
 * and the CV does not show come first. Resources come from a curated list only; a skill with no entry is listed without links.
 */
@Service
public class LearningPlanService {

    static final int MAX_ITEMS = 15;
    static final int MAX_JOBS = 40;

    public record Item(String skill, int jobs, int requiredIn, int preferredIn, List<LearningCatalog.Resource> resources) {
    }

    public record Plan(int jobsConsidered, List<Item> items) {
    }

    private final ApplicationRepository apps;
    private final MatchService matching;
    private final LearningCatalog catalog;

    LearningPlanService(ApplicationRepository apps, MatchService matching, LearningCatalog catalog) {
        this.apps = apps;
        this.matching = matching;
        this.catalog = catalog;
    }

    @Transactional
    public Plan plan(UUID userId, UUID cvId) {
        matching.requireCv(userId, cvId);
        List<MatchReport.LearningItem> gaps = new ArrayList<>();
        int considered = 0;
        for (JobApplication a : apps.findByUserIdOrderByUpdatedAtDesc(userId)) {
            if (a.getJobId() == null || a.getStatus().isFinal() || considered >= MAX_JOBS) {
                continue;
            }
            try {
                gaps.addAll(matching.match(userId, cvId, a.getJobId()).report().learningPriorities());
                considered++;
            } catch (RuntimeException e) {
                // The offer may have been removed since it was tracked; it simply adds nothing to the plan.
            }
        }
        return new Plan(considered, rank(gaps, catalog));
    }

    static List<Item> rank(List<MatchReport.LearningItem> gaps, LearningCatalog catalog) {
        record Acc(int jobs, int required, int preferred, int mentions) {
        }
        Map<String, Acc> acc = new HashMap<>();
        for (MatchReport.LearningItem g : gaps) {
            boolean required = "REQUIRED".equalsIgnoreCase(g.level());
            acc.merge(g.skill(), new Acc(1, required ? 1 : 0, required ? 0 : 1, g.mentions()),
                    (x, y) -> new Acc(x.jobs() + y.jobs(), x.required() + y.required(), x.preferred() + y.preferred(), x.mentions() + y.mentions()));
        }
        return acc.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Acc>>comparingInt(e -> -(e.getValue().required() * 3 + e.getValue().preferred()))
                        .thenComparingInt(e -> -e.getValue().required()).thenComparingInt(e -> -e.getValue().mentions())
                        .thenComparing(Map.Entry::getKey))
                .limit(MAX_ITEMS)
                .map(e -> new Item(e.getKey(), e.getValue().jobs(), e.getValue().required(), e.getValue().preferred(), catalog.forSkill(e.getKey())))
                .toList();
    }
}
