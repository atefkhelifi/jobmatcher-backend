package ai.jobmatcher.cv;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Structured candidate data extracted from a CV. The user reviews and corrects it before it is used downstream.
 * Unknown values stay {@code null} or empty; nothing is inferred beyond the text.
 */
public record ParsedCv(
        @Size(max = 160) String fullName,
        @Size(max = 254) String email,
        @Size(max = 40) String phone,
        @Size(max = 3000) String summary,
        @Size(max = 200) List<@Valid SkillEntry> skills,
        @Size(max = 40) List<@Valid Experience> experience,
        @Size(max = 30) List<@Valid Education> education,
        @Size(max = 20) List<@Valid LanguageLevel> languages,
        @Size(max = 40) List<@Size(max = 200) String> certifications,
        @Size(max = 40) List<@Size(max = 600) String> projects,
        Double yearsOfExperience) {

    public record SkillEntry(@Size(max = 80) String name, @Size(max = 40) String category, @Size(max = 300) String evidence) {
    }

    public record Experience(
            @Size(max = 160) String title,
            @Size(max = 160) String company,
            @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "must be YYYY-MM") String start,
            @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "must be YYYY-MM") String end,
            boolean current,
            @Size(max = 40) List<@Size(max = 800) String> bullets) {

        /** Drops the last bullet when it equals {@code line}; used when that line turned out to be the next job's title. */
        Experience withoutLastBullet(String line) {
            if (bullets == null || bullets.isEmpty() || !bullets.get(bullets.size() - 1).equals(line)) {
                return this;
            }
            return new Experience(title, company, start, end, current, List.copyOf(bullets.subList(0, bullets.size() - 1)));
        }
    }

    public record Education(@Size(max = 200) String degree, @Size(max = 200) String institution,
            @Pattern(regexp = "\\d{4}", message = "must be YYYY") String start,
            @Pattern(regexp = "\\d{4}", message = "must be YYYY") String end) {
    }

    public record LanguageLevel(@Size(max = 60) String language, @Size(max = 60) String level) {
    }

    public static ParsedCv empty() {
        return new ParsedCv(null, null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
    }
}
