package ai.jobmatcher.cv;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Deterministic CV text to {@link ParsedCv} conversion for French and English CVs.
 * It only reports what the text states; anything it cannot find stays empty so the user can fill it in.
 */
@Component
public class CvParser {

    enum Section { SUMMARY, EXPERIENCE, EDUCATION, SKILLS, LANGUAGES, CERTIFICATIONS, PROJECTS }

    private static final Map<Section, Pattern> HEADINGS = Map.of(
            Section.SUMMARY, heading("profile|professional profile|summary|professional summary|about me|about|profil|profil professionnel|résumé|resume|à propos|a propos|objectif"),
            Section.EXPERIENCE, heading("experience|experiences|work experience|professional experience|employment|employment history|work history|career|expérience|expériences|expérience professionnelle|expériences professionnelles|parcours professionnel"),
            Section.EDUCATION, heading("education|education and training|academic background|formation|formations|études|etudes|diplômes|diplomes|parcours académique|formation académique"),
            Section.SKILLS, heading("skills|technical skills|key skills|core skills|technologies|tech stack|compétences|competences|compétences techniques|competences techniques|compétences clés"),
            Section.LANGUAGES, heading("languages|language skills|langues|compétences linguistiques"),
            Section.CERTIFICATIONS, heading("certifications|certification|certificates|licenses|certificats|certifications et formations"),
            Section.PROJECTS, heading("projects|personal projects|selected projects|projets|projets personnels|réalisations|realisations"));

    private static Pattern heading(String alternatives) {
        return Pattern.compile("^(?:" + alternatives + ")$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static final String MONTH = "(?:jan\\w*|f[ée]v\\w*|feb\\w*|mar\\w*|apr\\w*|avr\\w*|may|mai|jun\\w*|juin|jul\\w*|juil\\w*|aug\\w*|ao[uû]t?|sep\\w*|oct\\w*|nov\\w*|d[ée]c\\w*|dec\\w*)";
    private static final Pattern RANGE = Pattern.compile(
            "(?:(?<sm>" + MONTH + "|\\d{1,2})\\.?[\\s/.\\-]*)?(?<sy>(?:19|20)\\d{2})\\s*(?:-|–|—|to|à|au|until)\\s*"
                    + "(?:(?:(?<em>" + MONTH + "|\\d{1,2})\\.?[\\s/.\\-]*)?(?<ey>(?:19|20)\\d{2})"
                    + "|(?<pr>present|présent|current|now|aujourd'hui|ce jour|en cours|à ce jour|today))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern YEAR = Pattern.compile("(?:19|20)\\d{2}");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<![\\d])(?:\\+|00)?\\d[\\d\\s().\\-]{7,16}\\d(?![\\d])");
    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-•*▪●◦·➢►–—]|\\d+[.)])\\s+");
    private static final Pattern SPLIT_HEADER = Pattern.compile("\\s+(?:[-–—|@]|chez|at)\\s+|\\s*\\|\\s*|,\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEGREE = Pattern.compile(
            "licence|master|bachelor|b\\.?sc|m\\.?sc|ph\\.?d|doctorat|ing[ée]nieur|dipl[oô]me|\\bdut\\b|\\bbts\\b|\\bmba\\b|baccalaur[ée]at|\\bbac\\s*\\+?\\d|engineer'?s? degree|degree",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern INSTITUTION = Pattern.compile(
            "universit|[ée]cole|school|institut|college|coll[èe]ge|\\biut\\b|polytech|insa|epita|esiea|faculty|facult[ée]|academy",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CEFR = Pattern.compile("\\b[ABC][12]\\b");
    private static final Map<String, String> LANGUAGE_NAMES = languageNames();

    private static Map<String, String> languageNames() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String[] p : new String[][] { { "english", "English" }, { "anglais", "English" }, { "french", "French" },
                { "français", "French" }, { "francais", "French" }, { "arabic", "Arabic" }, { "arabe", "Arabic" },
                { "spanish", "Spanish" }, { "espagnol", "Spanish" }, { "german", "German" }, { "allemand", "German" },
                { "italian", "Italian" }, { "italien", "Italian" }, { "portuguese", "Portuguese" },
                { "portugais", "Portuguese" }, { "chinese", "Chinese" }, { "chinois", "Chinese" }, { "dutch", "Dutch" },
                { "néerlandais", "Dutch" }, { "russian", "Russian" }, { "russe", "Russian" }, { "japanese", "Japanese" },
                { "japonais", "Japanese" } }) {
            m.put(p[0], p[1]);
        }
        return m;
    }

    // Common function words per language. Words that exist in several of these languages are left out on purpose.
    private static final Set<String> FR_WORDS = Set.of("le", "la", "les", "des", "et", "du", "pour", "avec", "dans", "une", "sur", "par",
            "vous", "nous", "est", "sont", "aux", "que", "qui", "plus", "votre", "notre", "entreprise", "poste", "équipe", "ses", "mon",
            "développeur", "ingénieur", "expérience", "compétences", "formation", "chez", "mise", "gestion");
    private static final Set<String> EN_WORDS = Set.of("the", "and", "of", "for", "with", "to", "you", "we", "our", "will", "are", "is",
            "be", "your", "team", "role", "their", "this", "that", "from", "have", "has", "or", "by", "developer", "engineer",
            "experience", "skills", "education", "at", "built", "managed", "using");
    private static final Set<String> DE_WORDS = Set.of("der", "das", "und", "mit", "für", "von", "zu", "den", "dem", "ein", "eine", "einen",
            "ist", "sind", "wir", "sie", "bei", "auf", "im", "nicht", "auch", "oder", "als", "aus", "werden", "ihre", "unser", "unsere",
            "du", "dich", "dein", "deine", "bewerbung", "erfahrung", "kenntnisse", "aufgaben", "bieten", "suchen", "entwickler");

    private final SkillCatalog catalog;

    public CvParser(SkillCatalog catalog) {
        this.catalog = catalog;
    }

    public ParsedCv parse(String rawText) {
        String text = normalize(rawText);
        List<String> lines = Arrays.asList(text.split("\n", -1));
        Map<Section, List<String>> sections = splitSections(lines);
        List<String> header = headerLines(lines);

        String email = firstMatch(EMAIL, text);
        String phone = firstMatch(PHONE, String.join("\n", header.isEmpty() ? lines.subList(0, Math.min(15, lines.size())) : header));
        String name = guessName(header.isEmpty() ? lines : header);
        String summary = sections.containsKey(Section.SUMMARY) ? join(sections.get(Section.SUMMARY)) : null;

        List<ParsedCv.Experience> experience = parseExperience(sections.getOrDefault(Section.EXPERIENCE, List.of()));
        return new ParsedCv(name, email, phone, blankToNull(summary), parseSkills(text, lines, sections), experience,
                parseEducation(sections.getOrDefault(Section.EDUCATION, List.of())),
                parseLanguages(sections.getOrDefault(Section.LANGUAGES, List.of())),
                bulletLines(sections.getOrDefault(Section.CERTIFICATIONS, List.of())),
                bulletLines(sections.getOrDefault(Section.PROJECTS, List.of())),
                yearsOfExperience(experience));
    }

    /** Returns "fr", "en", "de", or null when the text gives no clear signal. */
    public static String detectLanguage(String text) {
        int fr = 0;
        int en = 0;
        int de = 0;
        for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
            if (FR_WORDS.contains(w)) {
                fr++;
            }
            if (EN_WORDS.contains(w)) {
                en++;
            }
            if (DE_WORDS.contains(w)) {
                de++;
            }
        }
        int best = Math.max(fr, Math.max(en, de));
        int winners = (fr == best ? 1 : 0) + (en == best ? 1 : 0) + (de == best ? 1 : 0);
        if (best == 0 || winners > 1) {
            return null;
        }
        return fr == best ? "fr" : en == best ? "en" : "de";
    }

    // ---- structure ----

    static String normalize(String raw) {
        return raw.replace("\r\n", "\n").replace('\r', '\n').replace(' ', ' ').replace("\t", "  ")
                .replaceAll("[\\p{Cntrl}&&[^\n]]", "").replaceAll("[ ]{2,}", "  ").trim();
    }

    private Map<Section, List<String>> splitSections(List<String> lines) {
        Map<Section, List<String>> out = new LinkedHashMap<>();
        Section current = null;
        for (String line : lines) {
            Section s = sectionOf(line);
            if (s != null) {
                current = s;
                out.putIfAbsent(s, new ArrayList<>());
            } else if (current != null) {
                out.get(current).add(line);
            }
        }
        return out;
    }

    private List<String> headerLines(List<String> lines) {
        List<String> header = new ArrayList<>();
        for (String line : lines) {
            if (sectionOf(line) != null) {
                break;
            }
            header.add(line);
        }
        return header;
    }

    private Section sectionOf(String line) {
        String t = line.trim().replaceAll("[:：]+$", "").replaceAll("^[#=\\-_*\\s]+|[=\\-_*\\s]+$", "");
        if (t.isEmpty() || t.length() > 45) {
            return null;
        }
        for (Map.Entry<Section, Pattern> e : HEADINGS.entrySet()) {
            if (e.getValue().matcher(t).matches()) {
                return e.getKey();
            }
        }
        return null;
    }

    // ---- fields ----

    private String guessName(List<String> header) {
        for (String line : header) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.contains("@") || t.matches(".*\\d.*") || t.toLowerCase(Locale.ROOT).contains("http") || t.length() > 60
                    || t.split("\\s+").length > 5) {
                continue;
            }
            return t;
        }
        return null;
    }

    private List<ParsedCv.SkillEntry> parseSkills(String text, List<String> lines, Map<Section, List<String>> sections) {
        int[] starts = new int[lines.size()];
        int pos = 0;
        for (int i = 0; i < lines.size(); i++) {
            starts[i] = pos;
            pos += lines.get(i).length() + 1;
        }
        Set<String> skillLines = new TreeSet<>(sections.getOrDefault(Section.SKILLS, List.of()));
        List<ParsedCv.SkillEntry> out = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        for (SkillCatalog.Hit h : catalog.find(text)) {
            int idx = lineIndex(starts, h.start());
            String line = BULLET.matcher(lines.get(idx).trim()).replaceFirst("");
            String evidence = skillLines.contains(lines.get(idx)) ? "Listed in skills section" : cut(line, 200);
            out.add(new ParsedCv.SkillEntry(h.skill().canonical(), h.skill().category(), evidence));
            seen.add(h.skill().canonical().toLowerCase(Locale.ROOT));
        }
        // Skills the user listed that are not in the dictionary: keep them as written.
        for (String line : sections.getOrDefault(Section.SKILLS, List.of())) {
            for (String token : line.replaceAll("^[^:]{1,30}:", "").split("[,;•|·/]|\\s{2,}")) {
                String t = token.replaceAll("^[-–—*▪●◦\\s]+|[.\\s]+$", "");
                if (t.length() >= 2 && t.length() <= 40 && t.split("\\s+").length <= 4 && seen.add(t.toLowerCase(Locale.ROOT))) {
                    out.add(new ParsedCv.SkillEntry(t, "other", "Listed in skills section"));
                }
            }
        }
        return out;
    }

    private static int lineIndex(int[] starts, int offset) {
        int lo = 0;
        int hi = starts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (starts[mid] <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    private List<ParsedCv.Experience> parseExperience(List<String> lines) {
        List<ParsedCv.Experience> out = new ArrayList<>();
        String prevLine = null;
        ExpBuilder cur = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher m = RANGE.matcher(line);
            if (m.find()) {
                if (cur != null) {
                    out.add(cur.build());
                }
                cur = new ExpBuilder();
                String rest = (line.substring(0, m.start()) + " " + line.substring(m.end()))
                        .replaceAll("[()\\[\\]]", " ").replaceAll("^[\\s|,;:\\-–—•]+|[\\s|,;:\\-–—•]+$", "").trim();
                if (rest.isEmpty() && prevLine != null) {
                    rest = prevLine;
                    cur.dropFromPrevious = prevLine;
                }
                String[] parts = SPLIT_HEADER.split(rest, 2);
                cur.title = blankToNull(parts[0].trim());
                cur.company = parts.length > 1 ? blankToNull(parts[1].replaceAll("^[\\s|,\\-–—]+", "").trim()) : null;
                cur.start = monthIso(m.group("sm"), m.group("sy"));
                cur.current = m.group("pr") != null;
                cur.end = cur.current ? null : monthIso(m.group("em"), m.group("ey"));
                if (cur.dropFromPrevious != null && !out.isEmpty()) {
                    out.set(out.size() - 1, out.get(out.size() - 1).withoutLastBullet(cur.dropFromPrevious));
                }
            } else if (cur != null) {
                cur.addLine(line);
            }
            prevLine = BULLET.matcher(line).find() ? null : line;
        }
        if (cur != null) {
            out.add(cur.build());
        }
        return out;
    }

    private static final class ExpBuilder {
        String title;
        String company;
        String start;
        String end;
        boolean current;
        String dropFromPrevious;
        final List<String> bullets = new ArrayList<>();

        void addLine(String line) {
            Matcher b = BULLET.matcher(line);
            if (b.find()) {
                bullets.add(line.substring(b.end()).trim());
            } else if (!bullets.isEmpty() && Character.isLowerCase(line.charAt(0))) {
                int last = bullets.size() - 1;
                bullets.set(last, bullets.get(last) + " " + line);
            } else {
                bullets.add(line);
            }
        }

        ParsedCv.Experience build() {
            return new ParsedCv.Experience(title, company, start, end, current, List.copyOf(bullets));
        }
    }

    private List<ParsedCv.Education> parseEducation(List<String> lines) {
        List<ParsedCv.Education> out = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.trim().replaceAll("^[-•*▪●◦·\\s]+", "");
            if (line.isEmpty()) {
                continue;
            }
            List<String> years = new ArrayList<>();
            Matcher y = YEAR.matcher(line);
            while (y.find()) {
                years.add(y.group());
            }
            String clean = line.replaceAll("(?:19|20)\\d{2}", "").replaceAll("\\s*(?:-|–|—|to|à)\\s*(?=$)", "")
                    .replaceAll("[()]", " ").replaceAll("^[\\s|,;:\\-–—]+|[\\s|,;:\\-–—]+$", "").trim();
            if (DEGREE.matcher(clean).find()) {
                String degree = clean;
                String institution = null;
                for (String part : clean.split("\\s+[-–—|]\\s+|,\\s+")) {
                    if (INSTITUTION.matcher(part).find()) {
                        institution = part.trim();
                    }
                }
                if (institution != null) {
                    degree = clean.replace(institution, "").replaceAll("^[\\s|,;:\\-–—]+|[\\s|,;:\\-–—]+$", "").trim();
                }
                out.add(new ParsedCv.Education(blankToNull(degree), institution,
                        years.size() > 1 ? years.get(0) : null, years.isEmpty() ? null : years.get(years.size() - 1)));
            } else if (INSTITUTION.matcher(clean).find() && !out.isEmpty() && out.get(out.size() - 1).institution() == null) {
                ParsedCv.Education last = out.remove(out.size() - 1);
                out.add(new ParsedCv.Education(last.degree(), clean, last.start(), last.end()));
            } else if (INSTITUTION.matcher(clean).find()) {
                out.add(new ParsedCv.Education(null, clean, years.size() > 1 ? years.get(0) : null,
                        years.isEmpty() ? null : years.get(years.size() - 1)));
            }
        }
        return out;
    }

    private List<ParsedCv.LanguageLevel> parseLanguages(List<String> lines) {
        List<ParsedCv.LanguageLevel> out = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        for (String line : lines) {
            for (String item : line.split("[,;•|·\n]")) {
                String lower = item.toLowerCase(Locale.ROOT);
                for (Map.Entry<String, String> e : LANGUAGE_NAMES.entrySet()) {
                    int at = lower.indexOf(e.getKey());
                    if (at >= 0 && seen.add(e.getValue())) {
                        String rest = (item.substring(0, at) + " " + item.substring(at + e.getKey().length()))
                                .replaceAll("[()\\[\\]:]", " ").replaceAll("^[\\s\\-–—]+|[\\s\\-–—]+$", "").replaceAll("\\s{2,}", " ").trim();
                        Matcher c = CEFR.matcher(rest);
                        String level = c.find() ? c.group() : rest;
                        out.add(new ParsedCv.LanguageLevel(e.getValue(), blankToNull(level)));
                        break;
                    }
                }
            }
        }
        return out;
    }

    private List<String> bulletLines(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String raw : lines) {
            String t = BULLET.matcher(raw).replaceFirst("").trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    // ---- dates ----

    public static Double yearsOfExperience(List<ParsedCv.Experience> experience) {
        Set<Integer> months = new TreeSet<>();
        LocalDate now = LocalDate.now();
        for (ParsedCv.Experience e : experience) {
            Integer s = toMonthIndex(e.start());
            Integer end = e.current() ? now.getYear() * 12 + now.getMonthValue() : toMonthIndex(e.end());
            if (s != null && end != null && end >= s) {
                for (int i = s; i <= end; i++) {
                    months.add(i);
                }
            }
        }
        if (months.isEmpty()) {
            return null;
        }
        return Math.round(months.size() / 12.0 * 2) / 2.0;
    }

    private static Integer toMonthIndex(String iso) {
        if (iso == null || !iso.matches("\\d{4}-\\d{2}")) {
            return null;
        }
        return Integer.parseInt(iso.substring(0, 4)) * 12 + Integer.parseInt(iso.substring(5, 7));
    }

    /** yyyy-MM. An unknown month becomes July so the error is unbiased in either direction. */
    private static String monthIso(String month, String year) {
        if (year == null) {
            return null;
        }
        int m = monthNumber(month);
        return String.format("%s-%02d", year, m == 0 ? 7 : m);
    }

    private static int monthNumber(String token) {
        if (token == null) {
            return 0;
        }
        String t = token.toLowerCase(Locale.ROOT);
        if (t.matches("\\d{1,2}")) {
            int n = Integer.parseInt(t);
            return n >= 1 && n <= 12 ? n : 0;
        }
        String[][] prefixes = { { "jan", "1" }, { "feb", "2" }, { "fév", "2" }, { "fev", "2" }, { "mar", "3" },
                { "apr", "4" }, { "avr", "4" }, { "may", "5" }, { "mai", "5" }, { "juil", "7" }, { "jul", "7" },
                { "jun", "6" }, { "aug", "8" }, { "aoû", "8" }, { "aou", "8" }, { "sep", "9" }, { "oct", "10" },
                { "nov", "11" }, { "déc", "12" }, { "dec", "12" } };
        for (String[] p : prefixes) {
            if (t.startsWith(p[0])) {
                return Integer.parseInt(p[1]);
            }
        }
        return 0;
    }

    // ---- helpers ----

    private static String firstMatch(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group().trim() : null;
    }

    private static String join(List<String> lines) {
        return String.join(" ", lines.stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
