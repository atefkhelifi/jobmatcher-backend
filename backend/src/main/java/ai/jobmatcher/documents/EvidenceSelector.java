package ai.jobmatcher.documents;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import ai.jobmatcher.cv.ParsedCv;
import ai.jobmatcher.matching.MatchReport;
import ai.jobmatcher.profile.ProfileService.Preferences;

import org.springframework.stereotype.Component;

/**
 * Chooses which facts from the reviewed CV a letter or email may use, guided by the match report: the experience that
 * relates to the offer, the skills the offer asks for that the CV shows, years in related roles, education, and a
 * language the offer asks for. Every fact keeps the CV's own wording.
 */
@Component
public class EvidenceSelector {

    /** Everything a composer needs, with no free text beyond CV and offer wording. */
    public record Context(String jobTitle, String company, String candidateName, String email, String phone, String currentTitle,
            String relatedYears, String desiredTitle, List<Fact> facts, String corpus) {

        public List<Fact> ofType(Fact.Type t) {
            return facts.stream().filter(f -> f.type() == t).toList();
        }

        public List<String> skillNames() {
            return facts.stream().filter(f -> f.type() == Fact.Type.SKILL).flatMap(f -> f.skills().stream()).distinct().toList();
        }
    }

    public Context select(ParsedCv cv, MatchReport report, Preferences prefs, String jobTitle, String company, String jobText,
            String cvText, String fallbackName) {
        List<Fact> facts = new ArrayList<>();
        int n = 1;

        for (MatchReport.ExperienceHit e : report.relevantExperience()) {
            if (e.quote() == null || e.quote().isBlank()) {
                continue;
            }
            String period = e.start() == null ? null : e.start() + "/" + (e.current() ? "present" : e.end() == null ? "" : e.end());
            facts.add(new Fact("F" + n++, Fact.Type.EXPERIENCE, e.quote(), e.title(), e.company(), period, e.skills()));
        }

        int skillCount = 0;
        for (MatchReport.SkillResult s : report.skills()) {
            if (s.matched() && skillCount < 5) {
                facts.add(Fact.simple("F" + n++, Fact.Type.SKILL, s.evidence() == null ? s.skill() : s.evidence(), List.of(s.skill())));
                skillCount++;
            }
        }

        String years = relatedYears(report);
        if (years != null) {
            facts.add(Fact.simple("F" + n++, Fact.Type.YEARS, years + " years in roles related to the offer", List.of()));
        }

        ParsedCv.Education edu = cv.education().stream().filter(e -> e.degree() != null).findFirst().orElse(null);
        if (edu != null) {
            String text = edu.degree() + (edu.institution() == null ? "" : ", " + edu.institution()) + (edu.end() == null ? "" : " (" + edu.end() + ")");
            facts.add(Fact.simple("F" + n++, Fact.Type.EDUCATION, text, List.of()));
        }

        MatchReport.ComponentResult lang = report.components().stream().filter(c -> c.key().equals("languages") && c.known() && c.score() > 0).findFirst().orElse(null);
        if (lang != null) {
            List<String> needed = List.of(String.valueOf(lang.detail().params().getOrDefault("needed", "")).split(",\\s*"));
            String text = cv.languages().stream()
                    .filter(l -> l.language() != null && needed.stream().anyMatch(x -> x.equalsIgnoreCase(l.language())))
                    .map(l -> l.language() + (l.level() == null ? "" : " (" + l.level() + ")")).reduce((a, b) -> a + ", " + b).orElse(null);
            if (text != null) {
                facts.add(Fact.simple("F" + n++, Fact.Type.LANGUAGE, text, List.of()));
            }
        }

        String name = cv.fullName() != null ? cv.fullName() : fallbackName;
        String currentTitle = cv.experience().isEmpty() ? null : cv.experience().get(0).title();
        String corpus = String.join("\n", Objects.toString(cvText, ""), Objects.toString(jobTitle, ""), Objects.toString(company, ""),
                Objects.toString(jobText, ""), Objects.toString(name, ""), Objects.toString(years, "")).toLowerCase(Locale.ROOT);
        return new Context(jobTitle, company, name, cv.email(), cv.phone(), currentTitle, years, desiredTitle(report, prefs),
                List.copyOf(facts), corpus);
    }

    private static String relatedYears(MatchReport report) {
        return report.components().stream().filter(c -> c.key().equals("experience") && c.known() && c.detail().code().equals("experience.years"))
                .map(c -> c.detail().params().get("have")).filter(Objects::nonNull).map(Object::toString).map(EvidenceSelector::clean)
                .filter(s -> !s.equals("0")).findFirst().orElse(null);
    }

    private static String clean(String number) {
        return number.endsWith(".0") ? number.substring(0, number.length() - 2) : number;
    }

    /** One of the user's own desired titles, only when this offer's title genuinely resembles it. */
    private static String desiredTitle(MatchReport report, Preferences prefs) {
        if (prefs == null || prefs.desiredTitles() == null) {
            return null;
        }
        return report.components().stream().filter(c -> c.key().equals("title") && c.known() && c.score() >= 0.5)
                .map(c -> String.valueOf(c.detail().params().get("closest"))).filter(prefs.desiredTitles()::contains).findFirst().orElse(null);
    }
}
