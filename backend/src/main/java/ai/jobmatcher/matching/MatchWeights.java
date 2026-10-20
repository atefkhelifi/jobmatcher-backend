package ai.jobmatcher.matching;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Relative importance of each criterion (any non-negative numbers; they are renormalised over the criteria that can
 * be evaluated for a given offer). Change them under {@code app.matching.weights} in application.yml.
 */
@ConfigurationProperties(prefix = "app.matching.weights")
public record MatchWeights(
        @DefaultValue("30") int requiredSkills,
        @DefaultValue("20") int textSimilarity,
        @DefaultValue("15") int experience,
        @DefaultValue("10") int title,
        @DefaultValue("10") int preferredSkills,
        @DefaultValue("5") int languages,
        @DefaultValue("5") int location,
        @DefaultValue("5") int contractSalary) {

    public MatchWeights {
        for (int w : new int[] { requiredSkills, textSimilarity, experience, title, preferredSkills, languages, location, contractSalary }) {
            if (w < 0) {
                throw new IllegalArgumentException("app.matching.weights values must not be negative");
            }
        }
        if (requiredSkills + textSimilarity + experience + title + preferredSkills + languages + location + contractSalary == 0) {
            throw new IllegalArgumentException("app.matching.weights must not all be zero");
        }
    }

    public static MatchWeights defaults() {
        return new MatchWeights(30, 20, 15, 10, 10, 5, 5, 5);
    }

    /** Stable order, used for display. */
    public Map<String, Integer> asMap() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("requiredSkills", requiredSkills);
        m.put("textSimilarity", textSimilarity);
        m.put("experience", experience);
        m.put("title", title);
        m.put("preferredSkills", preferredSkills);
        m.put("languages", languages);
        m.put("location", location);
        m.put("contractSalary", contractSalary);
        return m;
    }
}
