package ai.jobmatcher.cv;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Renders a reviewed {@link ParsedCv} as Markdown. Empty sections are omitted; nothing is added. */
final class CvMarkdown {

    private CvMarkdown() {
    }

    static String render(ParsedCv cv) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(or(cv.fullName(), "Name")).append("\n\n");
        List<String> contact = new ArrayList<>();
        if (cv.email() != null) {
            contact.add(cv.email());
        }
        if (cv.phone() != null) {
            contact.add(cv.phone());
        }
        if (!contact.isEmpty()) {
            sb.append(String.join(" · ", contact)).append("\n\n");
        }
        if (cv.summary() != null && !cv.summary().isBlank()) {
            sb.append("## Summary\n\n").append(cv.summary().strip()).append("\n\n");
        }
        if (!cv.skills().isEmpty()) {
            sb.append("## Skills\n\n").append(cv.skills().stream().map(ParsedCv.SkillEntry::name).collect(Collectors.joining(", ")))
                    .append("\n\n");
        }
        if (!cv.experience().isEmpty()) {
            sb.append("## Experience\n\n");
            for (ParsedCv.Experience e : cv.experience()) {
                sb.append("### ").append(or(e.title(), "Role"));
                if (e.company() != null) {
                    sb.append(" — ").append(e.company());
                }
                sb.append("\n").append(or(e.start(), "?")).append(" – ").append(e.current() ? "present" : or(e.end(), "?")).append("\n\n");
                e.bullets().forEach(b -> sb.append("- ").append(b).append("\n"));
                sb.append("\n");
            }
        }
        if (!cv.education().isEmpty()) {
            sb.append("## Education\n\n");
            for (ParsedCv.Education e : cv.education()) {
                sb.append("- ").append(or(e.degree(), "Programme"));
                if (e.institution() != null) {
                    sb.append(", ").append(e.institution());
                }
                if (e.end() != null) {
                    sb.append(" (").append(e.start() != null ? e.start() + "–" : "").append(e.end()).append(")");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        if (!cv.languages().isEmpty()) {
            sb.append("## Languages\n\n");
            cv.languages().forEach(l -> sb.append("- ").append(l.language()).append(l.level() == null ? "" : " (" + l.level() + ")").append("\n"));
            sb.append("\n");
        }
        if (!cv.certifications().isEmpty()) {
            sb.append("## Certifications\n\n");
            cv.certifications().forEach(c -> sb.append("- ").append(c).append("\n"));
            sb.append("\n");
        }
        if (!cv.projects().isEmpty()) {
            sb.append("## Projects\n\n");
            cv.projects().forEach(p -> sb.append("- ").append(p).append("\n"));
        }
        return sb.toString().stripTrailing() + "\n";
    }

    private static String or(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}
