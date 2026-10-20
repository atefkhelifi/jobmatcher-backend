package ai.jobmatcher.learning;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * A hand-picked list of free learning resources per skill, read from {@code skills/learning-resources.csv}. A skill that is not
 * in the list has no resources; nothing is generated, so every link was put there by a person and can be link-checked.
 */
@Component
public class LearningCatalog {

    public record Resource(String title, String url, String provider, String kind, String language) {
    }

    private final Map<String, List<Resource>> bySkill = new LinkedHashMap<>();

    public LearningCatalog() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new ClassPathResource("skills/learning-resources.csv").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split(";", -1);
                if (p.length != 6 || !p[2].startsWith("https://")) {
                    throw new IllegalStateException("Bad learning resource line: " + line);
                }
                bySkill.computeIfAbsent(p[0].trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(new Resource(p[1].trim(), p[2].trim(), p[3].trim(), p[4].trim(), p[5].trim()));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load learning resources", e);
        }
    }

    public List<Resource> forSkill(String skill) {
        return skill == null ? List.of() : List.copyOf(bySkill.getOrDefault(skill.toLowerCase(Locale.ROOT), List.of()));
    }

    /** Lower-case skill names that have at least one resource. */
    public java.util.Set<String> skills() {
        return java.util.Set.copyOf(bySkill.keySet());
    }
}
