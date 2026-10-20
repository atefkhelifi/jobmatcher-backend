package ai.jobmatcher.cv;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Job-independent CV quality checks. Every finding is deterministic and points at text that exists in the CV.
 * Suggestions describe how to improve wording; they never supply facts, figures or employers.
 */
@Component
public class CvAnalyzer {

    public enum Severity { HIGH, MEDIUM, LOW }

    public record Finding(Severity severity, String category, String title, String detail, String suggestion,
            List<String> evidence) {
    }

    public record Report(int overallScore, String language, int wordCount, List<Finding> findings,
            Map<String, List<String>> skillInventory, List<String> strengths, List<String> gaps) {
    }

    private static final Pattern MEASURABLE = Pattern.compile(
            "\\d+\\s*(?:%|k\\b|m\\b|€|\\$|£|ms\\b|s\\b|users?|utilisateurs?|clients?|projects?|projets?|people|personnes|members|membres|x\\b)|\\d{2,}|[€$£]\\s*\\d",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WEAK_OPENING = Pattern.compile(
            "^(?:responsible for|in charge of|duties included|helped (?:with|to)|worked on|participated in|involved in|tasked with"
                    + "|responsable de|chargé de|charge de|en charge de|participation à|participé à|participe à|aide à|travaillé sur)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public Report analyze(String text, ParsedCv cv, String language) {
        List<Finding> f = new ArrayList<>();
        int words = text.isBlank() ? 0 : text.trim().split("\\s+").length;
        boolean fr = "fr".equals(language);

        checkContact(cv, f);
        checkSections(cv, f);
        checkLength(words, f);
        checkSummary(cv, f);
        checkExperience(cv, f, fr);
        checkSkills(cv, f);
        checkDates(cv, f);
        checkLayout(text, f);

        f.sort(Comparator.comparing(Finding::severity));
        int score = 100;
        for (Finding x : f) {
            score -= switch (x.severity()) {
                case HIGH -> 15;
                case MEDIUM -> 7;
                case LOW -> 3;
            };
        }
        score = Math.max(0, score);
        return new Report(score, language, words, List.copyOf(f), inventory(cv), strengths(cv), gaps(f));
    }

    // ---- checks ----

    private void checkContact(ParsedCv cv, List<Finding> f) {
        if (isBlank(cv.email())) {
            f.add(new Finding(Severity.HIGH, "ats", "No email address found",
                    "Recruiters and applicant tracking systems need a contact email near the top of the CV.",
                    "Add your professional email address in the header.", List.of()));
        }
        if (isBlank(cv.phone())) {
            f.add(new Finding(Severity.LOW, "ats", "No phone number found",
                    "A phone number is common practice, though not always required.",
                    "Add a phone number in the header if you are comfortable sharing it.", List.of()));
        }
    }

    private void checkSections(ParsedCv cv, List<Finding> f) {
        if (cv.experience().isEmpty()) {
            f.add(new Finding(Severity.HIGH, "structure", "No work experience section recognised",
                    "The parser found no dated experience entries. ATS software may not find them either.",
                    "Use a clear heading such as \"Experience\" and put each role's dates (for example \"2021 - 2024\") on the same line as the title.",
                    List.of()));
        }
        if (cv.education().isEmpty()) {
            f.add(new Finding(Severity.MEDIUM, "structure", "No education section recognised",
                    "No degree or institution was detected.",
                    "Add an \"Education\" section with your degree, institution and year, or confirm it is correctly written.",
                    List.of()));
        }
        if (cv.skills().isEmpty()) {
            f.add(new Finding(Severity.MEDIUM, "skills", "No skills detected",
                    "Matching and ATS filtering rely on explicit skills.",
                    "Add a \"Skills\" section listing the tools and technologies you have used.", List.of()));
        }
    }

    private void checkLength(int words, List<Finding> f) {
        if (words < 150) {
            f.add(new Finding(Severity.MEDIUM, "length", "The CV is very short (" + words + " words)",
                    "There may not be enough detail to assess or match you.",
                    "Describe each role with 3 to 5 bullet points covering what you did and what it achieved.", List.of()));
        } else if (words > 1100) {
            f.add(new Finding(Severity.MEDIUM, "length", "The CV is long (" + words + " words)",
                    "Most recruiters skim; more than two pages usually hides the strongest points.",
                    "Keep the most relevant roles in detail and condense older or less relevant ones.", List.of()));
        }
    }

    private void checkSummary(ParsedCv cv, List<Finding> f) {
        if (isBlank(cv.summary())) {
            f.add(new Finding(Severity.MEDIUM, "summary", "No professional summary",
                    "A 2 to 4 line summary tells a recruiter immediately who you are and what you are looking for.",
                    "Write a short summary stating your role, years of experience, main technologies and the kind of position you want.",
                    List.of()));
            return;
        }
        int w = cv.summary().trim().split("\\s+").length;
        if (w < 20) {
            f.add(new Finding(Severity.LOW, "summary", "The summary is very brief (" + w + " words)",
                    "It is too short to differentiate you.", "Expand it to 30 to 80 words with concrete strengths.",
                    List.of(cv.summary())));
        } else if (w > 120) {
            f.add(new Finding(Severity.LOW, "summary", "The summary is long (" + w + " words)",
                    "Long summaries are rarely read in full.", "Trim it to the three or four strongest points.", List.of()));
        }
    }

    private void checkExperience(ParsedCv cv, List<Finding> f, boolean fr) {
        List<String> bullets = new ArrayList<>();
        for (ParsedCv.Experience e : cv.experience()) {
            bullets.addAll(e.bullets());
        }
        if (bullets.isEmpty()) {
            return;
        }
        long measurable = bullets.stream().filter(b -> MEASURABLE.matcher(b).find()).count();
        double ratio = (double) measurable / bullets.size();
        if (ratio < 0.25) {
            f.add(new Finding(Severity.MEDIUM, "achievements", "Few measurable results (" + measurable + " of " + bullets.size() + " bullets)",
                    "Bullets with numbers (size, speed, savings, users, team) are more convincing than lists of duties.",
                    fr ? "Pour chaque mission, ajoutez un chiffre réel si vous en avez un : volume, délai gagné, taille d'équipe. N'ajoutez aucun chiffre que vous ne pouvez pas justifier."
                            : "For each role, add a real figure where you have one: volume, time saved, team size. Do not add any number you cannot back up.",
                    List.of()));
        }
        List<String> weak = bullets.stream().filter(b -> WEAK_OPENING.matcher(b.trim()).find()).limit(5).toList();
        if (!weak.isEmpty()) {
            f.add(new Finding(Severity.MEDIUM, "achievements", "Bullets describe duties instead of actions",
                    "Phrases such as \"responsible for\" say what the job was, not what you did.",
                    fr ? "Commencez par un verbe d'action (conçu, livré, réduit, piloté) puis ce que vous avez fait et son résultat vérifiable."
                            : "Start with an action verb (designed, delivered, reduced, led), then what you did and its verifiable result.",
                    weak));
        }
        Map<String, Integer> firstWords = new HashMap<>();
        for (String b : bullets) {
            String w = b.trim().split("\\s+")[0].toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]", "");
            if (w.length() > 2) {
                firstWords.merge(w, 1, Integer::sum);
            }
        }
        firstWords.entrySet().stream().filter(e -> e.getValue() >= 4).findFirst().ifPresent(e -> f.add(new Finding(
                Severity.LOW, "repetition", "Many bullets start with \"" + e.getKey() + "\" (" + e.getValue() + " times)",
                "Repetitive openings make the CV read as a template.", "Vary the opening verbs.", List.of())));
        long thin = cv.experience().stream().filter(e -> e.bullets().size() < 2).count();
        if (thin > 0) {
            f.add(new Finding(Severity.LOW, "experience", thin + " role(s) with fewer than two bullet points",
                    "Short entries give little evidence of what you accomplished.",
                    "Add one or two bullets describing your main contribution in each role.", List.of()));
        }
    }

    private void checkSkills(ParsedCv cv, List<Finding> f) {
        if (!cv.skills().isEmpty() && cv.skills().size() < 5) {
            f.add(new Finding(Severity.LOW, "skills", "Only " + cv.skills().size() + " skills detected",
                    "A short skill list limits how many offers can match.",
                    "List all tools and technologies you have actually used, grouped by category.", List.of()));
        }
        long unevidenced = cv.skills().stream().filter(s -> "Listed in skills section".equals(s.evidence())).count();
        if (cv.skills().size() >= 8 && unevidenced == cv.skills().size()) {
            f.add(new Finding(Severity.LOW, "skills", "Skills are listed but never shown in use",
                    "Every skill appears only in the skills list, none in an experience or project description.",
                    "Mention your key technologies inside the roles or projects where you used them.", List.of()));
        }
    }

    private void checkDates(ParsedCv cv, List<Finding> f) {
        List<ParsedCv.Experience> dated = cv.experience().stream().filter(e -> e.start() != null).toList();
        long undated = cv.experience().size() - dated.size();
        if (undated > 0) {
            f.add(new Finding(Severity.MEDIUM, "consistency", undated + " experience entry without usable dates",
                    "Entries without dates cannot be placed on your timeline.", "Add start and end dates to every role.", List.of()));
        }
        String now = LocalDate.now().toString().substring(0, 7);
        for (ParsedCv.Experience e : dated) {
            if (!e.current() && e.end() != null && e.end().compareTo(e.start()) < 0) {
                f.add(new Finding(Severity.HIGH, "consistency", "A role ends before it starts",
                        "Start " + e.start() + " is after end " + e.end() + ".", "Check and correct the dates.",
                        List.of(label(e))));
            }
            if (e.start().compareTo(now) > 0) {
                f.add(new Finding(Severity.MEDIUM, "consistency", "A role starts in the future",
                        "Start " + e.start() + " is later than today.", "Check the start date.", List.of(label(e))));
            }
        }
        List<ParsedCv.Experience> sorted = dated.stream().sorted(Comparator.comparing(ParsedCv.Experience::start)).toList();
        for (int i = 1; i < sorted.size(); i++) {
            ParsedCv.Experience prev = sorted.get(i - 1);
            ParsedCv.Experience cur = sorted.get(i);
            if (!prev.current() && prev.end() != null && gapMonths(prev.end(), cur.start()) > 6) {
                f.add(new Finding(Severity.LOW, "consistency", "Gap of more than six months between roles",
                        "Between " + prev.end() + " and " + cur.start() + ".",
                        "If it is relevant, say briefly what you did (study, project, freelance, career break).",
                        List.of(label(prev), label(cur))));
                break;
            }
        }
    }

    private void checkLayout(String text, List<Finding> f) {
        long wide = text.lines().filter(l -> l.contains("  ") && l.trim().split(" {2,}").length >= 3).count();
        if (wide >= 8) {
            f.add(new Finding(Severity.MEDIUM, "ats", "The layout may use columns or tables",
                    wide + " lines contain several widely separated text blocks. Some ATS read multi-column layouts out of order.",
                    "Prefer a single-column layout with standard headings, and check that the extracted text below reads in the right order.",
                    List.of()));
        }
    }

    // ---- summaries ----

    private Map<String, List<String>> inventory(ParsedCv cv) {
        Map<String, List<String>> out = new java.util.TreeMap<>();
        for (ParsedCv.SkillEntry s : cv.skills()) {
            out.computeIfAbsent(s.category() == null ? "other" : s.category(), k -> new ArrayList<>()).add(s.name());
        }
        return out;
    }

    private List<String> strengths(ParsedCv cv) {
        List<String> s = new ArrayList<>();
        if (cv.yearsOfExperience() != null) {
            s.add("About " + cv.yearsOfExperience() + " years of dated experience across " + cv.experience().size() + " role(s).");
        }
        if (cv.skills().size() >= 8) {
            s.add(cv.skills().size() + " distinct skills identified.");
        }
        long measurable = cv.experience().stream().flatMap(e -> e.bullets().stream()).filter(b -> MEASURABLE.matcher(b).find()).count();
        if (measurable >= 3) {
            s.add(measurable + " bullet points include measurable results.");
        }
        if (cv.languages().size() >= 2) {
            s.add("Languages stated: " + String.join(", ", cv.languages().stream().map(ParsedCv.LanguageLevel::language).toList()) + ".");
        }
        if (!cv.certifications().isEmpty()) {
            s.add(cv.certifications().size() + " certification(s) listed.");
        }
        return s;
    }

    private List<String> gaps(List<Finding> findings) {
        return findings.stream().filter(x -> x.severity() != Severity.LOW).map(Finding::title).toList();
    }

    private static String label(ParsedCv.Experience e) {
        return String.join(" · ", java.util.stream.Stream.of(e.title(), e.company(), e.start() + "–" + (e.current() ? "present" : e.end()))
                .filter(x -> x != null && !x.isBlank()).toList());
    }

    private static int gapMonths(String endIso, String startIso) {
        int a = Integer.parseInt(endIso.substring(0, 4)) * 12 + Integer.parseInt(endIso.substring(5, 7));
        int b = Integer.parseInt(startIso.substring(0, 4)) * 12 + Integer.parseInt(startIso.substring(5, 7));
        return b - a;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
