package ai.jobmatcher.matching;

import java.util.List;
import java.util.Map;

/**
 * The explanation behind one score. Free text is expressed as a {@code code} plus parameters so the interface can
 * show it in the user's language; the numbers and names in {@code params} come from the CV and the offer only.
 */
public record MatchReport(
        Integer score,
        int coverage,
        boolean insufficientData,
        List<ComponentResult> components,
        List<SkillResult> skills,
        List<Note> strengths,
        List<Note> gaps,
        List<ExperienceHit> relevantExperience,
        List<Note> blockers,
        List<Note> cvAdjustments,
        List<LearningItem> learningPriorities,
        boolean cvReviewed) {

    /** A message the UI renders from its own translation of {@code code}. */
    public record Note(String code, Map<String, Object> params) {
        public static Note of(String code, Object... kv) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            for (int i = 0; i + 1 < kv.length; i += 2) {
                m.put(String.valueOf(kv[i]), kv[i + 1]);
            }
            return new Note(code, m);
        }
    }

    /**
     * One weighted criterion. {@code score} is null when it could not be evaluated, which is neutral, never negative.
     * {@code confidence} (0 to 1) scales the weight when little evidence backs the value, for example when an ad names
     * a single skill and does not say whether it is required.
     */
    public record ComponentResult(String key, int weight, Double score, Note detail, double confidence) {
        public ComponentResult(String key, int weight, Double score, Note detail) {
            this(key, weight, score, detail, 1.0);
        }

        public boolean known() {
            return score != null;
        }

        public double effectiveWeight() {
            return weight * confidence;
        }
    }

    /** {@code level} is REQUIRED or PREFERRED; {@code inferred} marks skills the ad mentions without saying which kind. */
    public record SkillResult(String skill, String level, boolean inferred, boolean matched, String evidence, int mentions) {
    }

    public record ExperienceHit(String title, String company, String start, String end, boolean current, String quote,
            List<String> skills) {
    }

    public record LearningItem(String skill, String level, int mentions) {
    }
}
