package ai.jobmatcher.cv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Curated French/English skill dictionary (canonical name, category, aliases) loaded from {@code skills/skills.csv}.
 * Short or word-like aliases are matched case-sensitively to avoid reading "go" or "express" as technologies.
 */
@Component
public class SkillCatalog {

    public record Skill(String canonical, String category) {
    }

    public record Hit(Skill skill, int start, int end) {
    }

    private static final Set<String> AMBIGUOUS = Set.of("go", "express", "spring", "swift", "rust", "ruby", "flask",
            "oracle", "agile", "security", "rest");

    private record Matcher(Skill skill, Pattern pattern) {
    }

    private final List<Matcher> matchers = new ArrayList<>();
    private final Map<String, Skill> byLowerName = new LinkedHashMap<>();

    public SkillCatalog() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new ClassPathResource("skills/skills.csv").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split(";", 3);
                Skill skill = new Skill(parts[0].trim(), parts[1].trim());
                byLowerName.put(skill.canonical().toLowerCase(Locale.ROOT), skill);
                for (String alias : parts[2].split("\\|")) {
                    matchers.add(new Matcher(skill, compile(alias.trim())));
                }
                matchers.add(new Matcher(skill, compile(skill.canonical())));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load skills dictionary", e);
        }
    }

    private static Pattern compile(String alias) {
        String body = Pattern.quote(alias).replace("\\ ", "\\E\\s+\\Q");
        String guarded = "(?<![\\p{L}\\p{N}+#.])" + body + "(?![\\p{L}\\p{N}+#])";
        String lower = alias.toLowerCase(Locale.ROOT);
        boolean strict = AMBIGUOUS.contains(lower) || alias.length() <= 2;
        if (!strict) {
            return Pattern.compile(guarded, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        }
        // Only the capitalised or upper-case spelling counts, e.g. "Go", "GO", "Spring", never "go" in prose.
        String cap = Character.toUpperCase(alias.charAt(0)) + alias.substring(1);
        String forms = Pattern.quote(cap) + "|" + Pattern.quote(alias.toUpperCase(Locale.ROOT));
        return Pattern.compile("(?<![\\p{L}\\p{N}+#.])(?:" + forms + ")(?![\\p{L}\\p{N}+#])");
    }

    /** All distinct skills mentioned in the text, in order of first appearance, with the match position. */
    public List<Hit> find(String text) {
        List<Hit> all = new java.util.ArrayList<>();
        for (Matcher m : matchers) {
            java.util.regex.Matcher mm = m.pattern().matcher(text);
            while (mm.find()) {
                all.add(new Hit(m.skill(), mm.start(), mm.end()));
            }
        }
        // A mention inside a longer skill name of a different skill ("Spring" in "Spring Boot") is not a separate mention.
        Map<String, Hit> found = new LinkedHashMap<>();
        for (Hit h : all) {
            boolean swallowed = all.stream().anyMatch(o -> !o.skill().canonical().equals(h.skill().canonical())
                    && o.start() <= h.start() && o.end() >= h.end() && (o.end() - o.start()) > (h.end() - h.start()));
            if (swallowed) {
                continue;
            }
            Hit prev = found.get(h.skill().canonical());
            if (prev == null || h.start() < prev.start()) {
                found.put(h.skill().canonical(), h);
            }
        }
        return found.values().stream().sorted(java.util.Comparator.comparingInt(Hit::start)).toList();
    }

    public Skill lookup(String name) {
        return name == null ? null : byLowerName.get(name.toLowerCase(Locale.ROOT));
    }

    public int size() {
        return byLowerName.size();
    }
}
