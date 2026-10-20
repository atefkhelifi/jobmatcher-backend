package ai.jobmatcher.matching;

import static ai.jobmatcher.matching.MatchReport.Note;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ai.jobmatcher.cv.ParsedCv;
import ai.jobmatcher.cv.SkillCatalog;
import ai.jobmatcher.jobs.RemoteMode;
import ai.jobmatcher.profile.ProfileService.Preferences;

import org.springframework.stereotype.Component;

/**
 * Explainable compatibility score from 0 to 100 between a reviewed CV (plus the user's stated preferences) and one offer.
 *
 * <p>Method: each criterion yields a value in [0, 1] or "unknown". The score is the weighted mean over the criteria that
 * could be evaluated, so a missing piece of information (no salary in the ad, no preferred cities in the profile) is left
 * out instead of counting against the candidate. {@code coverage} reports how much of the weighting was evaluated.
 * Hard problems (visa, closed offer, salary below the stated minimum) are shown as blockers next to the score, not
 * hidden inside it. Only job-relevant evidence is read: name, email, phone and similar fields are never used.
 * The result is a measure of fit with the posting, not a probability of being hired.
 */
@Component
public class MatchScorer {

    /** What the scorer needs to know about an offer. */
    public record JobFacts(String title, String description, String location, String country, RemoteMode remote,
            String employmentType, Integer salaryMin, Integer salaryMax, String salaryCurrency, boolean expired) {
    }

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern YEARS = Pattern.compile("(\\d{1,2})\\s*\\+?\\s*(?:years?|yrs?|ans|ann[ée]es)", FLAGS);
    private static final Pattern EXPERIENCE_WORD = Pattern.compile("exp[ée]rience|experience|\\bexp\\b", FLAGS);
    private static final Pattern VISA = Pattern.compile(
            "no (?:visa )?sponsorship|not (?:able|willing|in a position) to sponsor|unable to sponsor|cannot sponsor|can't sponsor|"
                    + "(?:cannot|can't|unable to|do not|don't|does not|won't|will not|not able to) (?:offer|provide|sponsor|support)\\b[^.\\n]{0,25}\\b(?:sponsor\\w*|visas?|work permits?)|"
                    + "sponsorship (?:is )?not (?:available|offered|provided)|must (?:already )?(?:have|hold|possess) (?:the )?(?:legal )?right to work|"
                    + "work permit (?:is )?(?:required|mandatory)|ne propose pas de (?:visa|sponsoring)|pas de sponsoring|"
                    + "permis de travail (?:requis|obligatoire)|keine visa",
            FLAGS);
    private static final Pattern LANG_CUE = Pattern.compile(
            "fluent|native|courant|bilingue|bilingual|mother tongue|langue maternelle|required|requis|obligatoire|proficien|ma[iî]trise|"
                    + "professional working|\\b[BC][12]\\b|business level|written and spoken|[ée]crit et oral|excellent",
            FLAGS);
    private static final Map<String, String> LANGUAGE_NAMES = Map.ofEntries(Map.entry("english", "English"),
            Map.entry("anglais", "English"), Map.entry("french", "French"), Map.entry("français", "French"),
            Map.entry("francais", "French"), Map.entry("german", "German"), Map.entry("allemand", "German"),
            Map.entry("deutsch", "German"), Map.entry("spanish", "Spanish"), Map.entry("espagnol", "Spanish"),
            Map.entry("italian", "Italian"), Map.entry("italien", "Italian"), Map.entry("dutch", "Dutch"),
            Map.entry("néerlandais", "Dutch"), Map.entry("portuguese", "Portuguese"), Map.entry("portugais", "Portuguese"),
            Map.entry("arabic", "Arabic"), Map.entry("arabe", "Arabic"));
    private static final Pattern GENDER = Pattern.compile("\\([^)]{1,12}\\)|\\b[mfhwdx]/[mfhwdx](?:/[mfhwdx])?\\b", FLAGS);
    private static final Set<String> LEVEL_WORDS = Set.of("junior", "senior", "lead", "principal", "staff", "confirmé", "confirme",
            "expérimenté", "experimente", "stagiaire", "intern", "internship", "trainee", "head", "jr", "sr", "mid", "intermediate");
    private static final Map<String, String> TITLE_SYNONYMS = new LinkedHashMap<>();

    static {
        for (String s : new String[] { "développeur", "developpeur", "developer", "dev", "programmeur", "développeuse", "developpeuse" }) {
            TITLE_SYNONYMS.put(s, "developer");
        }
        for (String s : new String[] { "ingénieur", "ingenieur", "engineer", "ingénieure" }) {
            TITLE_SYNONYMS.put(s, "engineer");
        }
        TITLE_SYNONYMS.put("fullstack", "fullstack");
        TITLE_SYNONYMS.put("full-stack", "fullstack");
        TITLE_SYNONYMS.put("back-end", "backend");
        TITLE_SYNONYMS.put("front-end", "frontend");
    }

    private final SkillCatalog catalog;
    private final JobSkillExtractor extractor;
    private final MatchWeights weights;

    public MatchScorer(SkillCatalog catalog, JobSkillExtractor extractor, MatchWeights weights) {
        this.catalog = catalog;
        this.extractor = extractor;
        this.weights = weights;
    }

    public MatchReport score(ParsedCv cv, Preferences prefs, JobFacts job, boolean cvReviewed) {
        // ---- skills ----
        List<JobSkillExtractor.JobSkill> found = extractor.extract(job.title(), job.description());
        boolean hasRequired = found.stream().anyMatch(s -> s.level() == JobSkillExtractor.Level.REQUIRED);
        Map<String, String> mine = candidateSkills(cv);
        List<MatchReport.SkillResult> skills = new ArrayList<>();
        for (JobSkillExtractor.JobSkill s : found) {
            boolean inferred = s.level() == JobSkillExtractor.Level.MENTIONED;
            String level = s.level() == JobSkillExtractor.Level.REQUIRED ? "REQUIRED"
                    : s.level() == JobSkillExtractor.Level.PREFERRED ? "PREFERRED" : hasRequired ? "PREFERRED" : "REQUIRED";
            String evidence = mine.get(s.skill().toLowerCase(Locale.ROOT));
            skills.add(new MatchReport.SkillResult(s.skill(), level, inferred, evidence != null, evidence, s.mentions()));
        }
        List<MatchReport.SkillResult> required = skills.stream().filter(s -> s.level().equals("REQUIRED")).toList();
        List<MatchReport.SkillResult> preferred = skills.stream().filter(s -> s.level().equals("PREFERRED")).toList();

        // ---- criteria ----
        Set<String> jobSkillKeys = new LinkedHashSet<>();
        skills.forEach(sk -> jobSkillKeys.add(sk.skill().toLowerCase(Locale.ROOT)));
        Double relevantYears = relevantYears(cv, jobSkillKeys, titleTokens(job.title()));
        List<MatchReport.ComponentResult> comps = new ArrayList<>();
        comps.add(skillComponent("requiredSkills", weights.requiredSkills(), required, "required"));
        comps.add(similarity(cv, job));
        comps.add(experience(cv, prefs, job, relevantYears));
        comps.add(title(cv, prefs, job));
        comps.add(skillComponent("preferredSkills", weights.preferredSkills(), preferred, "preferred"));
        MatchReport.ComponentResult lang = languages(cv, job);
        comps.add(lang);
        comps.add(location(prefs, job));
        comps.add(contractSalary(prefs, job));

        double known = 0;
        double all = 0;
        double sum = 0;
        for (MatchReport.ComponentResult c : comps) {
            all += c.weight();
            if (c.known()) {
                known += c.effectiveWeight();
                sum += c.effectiveWeight() * c.score();
            }
        }
        boolean insufficient = known == 0;
        Integer score = insufficient ? null : (int) Math.round(100.0 * sum / known);
        int coverage = all == 0 ? 0 : (int) Math.round(100.0 * known / all);

        return new MatchReport(score, coverage, insufficient, List.copyOf(comps), List.copyOf(skills),
                strengths(comps, required, preferred, relevantYears, job), gaps(comps, required, preferred, lang, relevantYears, job),
                relevantExperience(cv, skills), blockers(prefs, job, comps), adjustments(required, cvReviewed),
                learning(required, preferred), cvReviewed);
    }

    // ---- criteria ----

    private MatchReport.ComponentResult skillComponent(String key, int weight, List<MatchReport.SkillResult> list, String kind) {
        if (list.isEmpty()) {
            return new MatchReport.ComponentResult(key, weight, null, Note.of(kind + ".none"));
        }
        long matched = list.stream().filter(MatchReport.SkillResult::matched).count();
        // One named skill is thin evidence, and skills the ad only mentions in passing are weaker still.
        double inferredShare = (double) list.stream().filter(MatchReport.SkillResult::inferred).count() / list.size();
        double confidence = Math.min(1.0, list.size() / 3.0) * (1.0 - 0.5 * inferredShare);
        return new MatchReport.ComponentResult(key, weight, (double) matched / list.size(),
                Note.of(kind + ".some", "matched", matched, "total", list.size()), confidence);
    }

    private MatchReport.ComponentResult similarity(ParsedCv cv, JobFacts job) {
        String mineText = cvText(cv);
        String theirs = (job.title() == null ? "" : job.title()) + "\n" + (job.description() == null ? "" : job.description());
        if (mineText.isBlank() || theirs.isBlank()) {
            return new MatchReport.ComponentResult("textSimilarity", weights.textSimilarity(), null, Note.of("text.unknown"));
        }
        // Typical cosine values for a good fit are 0.2 to 0.35; 0.30 and above counts as a full match.
        double s = Math.min(1.0, TextSimilarity.cosine(mineText, theirs) / 0.30);
        return new MatchReport.ComponentResult("textSimilarity", weights.textSimilarity(), s, Note.of("text.some", "pct", Math.round(s * 100)));
    }

    private MatchReport.ComponentResult experience(ParsedCv cv, Preferences prefs, JobFacts job, Double relevantYears) {
        int w = weights.experience();
        Integer need = requiredYears(job.description());
        if (need != null && relevantYears != null) {
            double s = Math.min(1.0, relevantYears / need);
            return new MatchReport.ComponentResult("experience", w, s,
                    Note.of("experience.years", "have", relevantYears, "need", need, "total", cv.yearsOfExperience() == null ? 0 : cv.yearsOfExperience()));
        }
        Double have = cv.yearsOfExperience();
        String jobLevel = seniorityOf(job.title());
        String mineLevel = prefs != null && prefs.seniority() != null ? prefs.seniority() : have != null ? levelFromYears(have) : null;
        if (jobLevel != null && mineLevel != null) {
            int diff = rank(jobLevel) - rank(mineLevel);
            double s = switch (diff) {
                case 0 -> 1.0;
                case 1 -> 0.6;
                case 2 -> 0.25;
                case 3 -> 0.1;
                case -1 -> 0.8;
                case -2 -> 0.6;
                default -> 0.4;
            };
            return new MatchReport.ComponentResult("experience", w, s, Note.of("experience.seniority", "job", jobLevel, "you", mineLevel));
        }
        return new MatchReport.ComponentResult("experience", w, null, Note.of("experience.unknown"));
    }

    /**
     * Years in roles that relate to this offer: a role counts when its title or bullets name one of the offer's skills, or
     * its title shares a word with the offer's title. Null when the CV has no dated roles at all.
     */
    private Double relevantYears(ParsedCv cv, Set<String> jobSkillKeys, Set<String> jobTitle) {
        if (cv.yearsOfExperience() == null) {
            return null;
        }
        List<ParsedCv.Experience> related = new ArrayList<>();
        for (ParsedCv.Experience e : cv.experience()) {
            boolean skillHit = textsOf(e).stream().anyMatch(t -> catalog.find(t).stream()
                    .anyMatch(h -> jobSkillKeys.contains(h.skill().canonical().toLowerCase(Locale.ROOT))));
            boolean titleHit = !Collections.disjoint(titleTokens(e.title()), jobTitle);
            if (skillHit || titleHit) {
                related.add(e);
            }
        }
        Double years = ai.jobmatcher.cv.CvParser.yearsOfExperience(related);
        return years == null ? 0.0 : years;
    }

    private MatchReport.ComponentResult title(ParsedCv cv, Preferences prefs, JobFacts job) {
        List<String> mine = new ArrayList<>();
        if (prefs != null && prefs.desiredTitles() != null) {
            mine.addAll(prefs.desiredTitles());
        }
        for (int i = 0; i < Math.min(2, cv.experience().size()); i++) {
            String t = cv.experience().get(i).title();
            if (t != null && !t.isBlank()) {
                mine.add(t);
            }
        }
        Set<String> theirs = titleTokens(job.title());
        if (mine.isEmpty() || theirs.isEmpty()) {
            return new MatchReport.ComponentResult("title", weights.title(), null, Note.of("title.unknown"));
        }
        double best = 0;
        String bestTitle = null;
        for (String m : mine) {
            Set<String> mt = titleTokens(m);
            if (mt.isEmpty()) {
                continue;
            }
            Set<String> inter = new LinkedHashSet<>(mt);
            inter.retainAll(theirs);
            double dice = 2.0 * inter.size() / (mt.size() + theirs.size());
            if (dice > best) {
                best = dice;
                bestTitle = m;
            }
        }
        return new MatchReport.ComponentResult("title", weights.title(), best, Note.of("title.some", "pct", Math.round(best * 100), "closest", bestTitle == null ? "" : bestTitle));
    }

    private MatchReport.ComponentResult languages(ParsedCv cv, JobFacts job) {
        Set<String> needed = new LinkedHashSet<>();
        for (String sentence : (job.description() == null ? "" : job.description()).split("(?<=[.!?\\n])")) {
            String lower = sentence.toLowerCase(Locale.ROOT);
            if (!LANG_CUE.matcher(lower).find()) {
                continue;
            }
            for (Map.Entry<String, String> e : LANGUAGE_NAMES.entrySet()) {
                if (Pattern.compile("(?<![\\p{L}])" + e.getKey() + "(?![\\p{L}])").matcher(lower).find()) {
                    needed.add(e.getValue());
                }
            }
        }
        if (needed.isEmpty()) {
            return new MatchReport.ComponentResult("languages", weights.languages(), null, Note.of("languages.none"));
        }
        Set<String> mine = new LinkedHashSet<>();
        for (ParsedCv.LanguageLevel l : cv.languages()) {
            if (l.language() != null) {
                mine.add(l.language().toLowerCase(Locale.ROOT));
            }
        }
        if (cv.languages().isEmpty()) {
            // The CV states no languages at all, so this cannot be judged.
            return new MatchReport.ComponentResult("languages", weights.languages(), null, Note.of("languages.cvUnknown", "needed", String.join(", ", needed)));
        }
        long matched = needed.stream().filter(n -> mine.contains(n.toLowerCase(Locale.ROOT))).count();
        return new MatchReport.ComponentResult("languages", weights.languages(), (double) matched / needed.size(),
                Note.of("languages.some", "matched", matched, "total", needed.size(), "needed", String.join(", ", needed)));
    }

    private MatchReport.ComponentResult location(Preferences prefs, JobFacts job) {
        int w = weights.location();
        List<String> modes = prefs == null || prefs.workModes() == null ? List.of() : prefs.workModes();
        List<String> cities = prefs == null || prefs.preferredLocations() == null ? List.of() : prefs.preferredLocations();
        List<String> countries = prefs == null || prefs.preferredCountries() == null ? List.of() : prefs.preferredCountries();
        boolean relocate = prefs != null && prefs.willingToRelocate();
        if (modes.isEmpty() && cities.isEmpty() && countries.isEmpty()) {
            return new MatchReport.ComponentResult("location", w, null, Note.of("location.noPrefs"));
        }
        String loc = job.location() == null ? "" : job.location().toLowerCase(Locale.ROOT);
        boolean cityMatch = cities.stream().anyMatch(c -> !c.isBlank() && loc.contains(c.toLowerCase(Locale.ROOT)));
        boolean countryMatch = job.country() != null && countries.contains(job.country());
        boolean placePrefs = !cities.isEmpty() || !countries.isEmpty();
        RemoteMode rm = job.remote() == null ? RemoteMode.UNKNOWN : job.remote();

        if (rm == RemoteMode.REMOTE) {
            if (modes.isEmpty() || modes.contains("remote")) {
                return new MatchReport.ComponentResult("location", w, 1.0, Note.of("location.remoteOk"));
            }
            return new MatchReport.ComponentResult("location", w, cityMatch ? 0.7 : 0.5, Note.of("location.remoteNotWanted"));
        }
        if (rm == RemoteMode.UNKNOWN) {
            if (!placePrefs || (loc.isBlank() && job.country() == null)) {
                return new MatchReport.ComponentResult("location", w, null, Note.of("location.unknown"));
            }
            if (cityMatch) {
                return new MatchReport.ComponentResult("location", w, 1.0, Note.of("location.cityMatch"));
            }
            if (countryMatch) {
                return new MatchReport.ComponentResult("location", w, 0.8, Note.of("location.countryMatch"));
            }
            return new MatchReport.ComponentResult("location", w, relocate ? 0.6 : 0.3, Note.of(relocate ? "location.relocate" : "location.far"));
        }
        boolean modeOk = modes.isEmpty() || modes.contains(rm == RemoteMode.HYBRID ? "hybrid" : "onsite");
        if (cityMatch) {
            return new MatchReport.ComponentResult("location", w, modeOk ? 1.0 : 0.6, Note.of(modeOk ? "location.cityMatch" : "location.modeMismatch"));
        }
        if (!placePrefs) {
            return new MatchReport.ComponentResult("location", w, modeOk ? 1.0 : 0.4, Note.of(modeOk ? "location.modeOk" : "location.modeMismatch"));
        }
        if (countryMatch && cities.isEmpty()) {
            return new MatchReport.ComponentResult("location", w, modeOk ? 0.8 : 0.5, Note.of("location.countryMatch"));
        }
        return new MatchReport.ComponentResult("location", w, relocate ? 0.6 : 0.2, Note.of(relocate ? "location.relocate" : "location.far"));
    }

    private MatchReport.ComponentResult contractSalary(Preferences prefs, JobFacts job) {
        int w = weights.contractSalary();
        List<Double> parts = new ArrayList<>();
        String contract = "unknown";
        String salary = "unknown";
        if (prefs != null && prefs.contractTypes() != null && !prefs.contractTypes().isEmpty()) {
            String type = contractCategory(job.employmentType());
            if (type != null) {
                boolean fits = prefs.contractTypes().contains(type);
                parts.add(fits ? 1.0 : 0.2);
                contract = fits ? "fits" : "mismatch";
            }
        }
        Integer wanted = prefs == null ? null : prefs.salaryMin();
        if (wanted != null && wanted > 0 && job.salaryMax() != null && job.salaryCurrency() != null
                && job.salaryCurrency().equalsIgnoreCase(prefs.salaryCurrency() == null ? "" : prefs.salaryCurrency())) {
            double s = job.salaryMax() >= wanted ? 1.0 : (double) job.salaryMax() / wanted;
            parts.add(s);
            salary = job.salaryMax() >= wanted ? "ok" : "low";
        }
        if (parts.isEmpty()) {
            return new MatchReport.ComponentResult("contractSalary", w, null, Note.of("contract.unknown"));
        }
        double avg = parts.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return new MatchReport.ComponentResult("contractSalary", w, avg, Note.of("contract.some", "contract", contract, "salary", salary));
    }

    // ---- explanation ----

    private List<Note> strengths(List<MatchReport.ComponentResult> comps, List<MatchReport.SkillResult> req,
            List<MatchReport.SkillResult> pref, Double relevantYears, JobFacts job) {
        List<Note> out = new ArrayList<>();
        List<String> matchedReq = req.stream().filter(MatchReport.SkillResult::matched).map(MatchReport.SkillResult::skill).toList();
        if (!matchedReq.isEmpty()) {
            out.add(Note.of("strength.requiredSkills", "matched", matchedReq.size(), "total", req.size(), "skills", String.join(", ", matchedReq.stream().limit(6).toList())));
        }
        List<String> matchedPref = pref.stream().filter(MatchReport.SkillResult::matched).map(MatchReport.SkillResult::skill).toList();
        if (!matchedPref.isEmpty()) {
            out.add(Note.of("strength.preferredSkills", "skills", String.join(", ", matchedPref.stream().limit(6).toList())));
        }
        Integer need = requiredYears(job.description());
        if (need != null && relevantYears != null && relevantYears >= need) {
            out.add(Note.of("strength.experience", "have", relevantYears, "need", need));
        }
        for (MatchReport.ComponentResult c : comps) {
            if (c.known() && c.score() >= 0.7 && c.key().equals("title")) {
                out.add(Note.of("strength.title", "closest", c.detail().params().get("closest")));
            }
            if (c.known() && c.score() >= 0.9 && c.key().equals("location")) {
                out.add(Note.of("strength.location"));
            }
            if (c.known() && c.score() >= 0.9 && c.key().equals("languages")) {
                out.add(Note.of("strength.languages", "languages", c.detail().params().get("needed")));
            }
        }
        return out;
    }

    private List<Note> gaps(List<MatchReport.ComponentResult> comps, List<MatchReport.SkillResult> req, List<MatchReport.SkillResult> pref,
            MatchReport.ComponentResult lang, Double relevantYears, JobFacts job) {
        List<Note> out = new ArrayList<>();
        List<String> missReq = req.stream().filter(s -> !s.matched()).map(MatchReport.SkillResult::skill).toList();
        if (!missReq.isEmpty()) {
            out.add(Note.of("gap.requiredSkills", "count", missReq.size(), "skills", String.join(", ", missReq)));
        }
        List<String> missPref = pref.stream().filter(s -> !s.matched()).map(MatchReport.SkillResult::skill).toList();
        if (!missPref.isEmpty()) {
            out.add(Note.of("gap.preferredSkills", "count", missPref.size(), "skills", String.join(", ", missPref)));
        }
        Integer need = requiredYears(job.description());
        if (need != null && relevantYears != null && relevantYears < need) {
            out.add(Note.of("gap.experience", "have", relevantYears, "need", need));
        }
        if (lang.known() && lang.score() < 1.0) {
            out.add(Note.of("gap.languages", "languages", lang.detail().params().get("needed")));
        }
        comps.stream().filter(c -> c.key().equals("location") && c.known() && c.score() < 0.5).findFirst()
                .ifPresent(c -> out.add(Note.of("gap.location")));
        return out;
    }

    private List<Note> blockers(Preferences prefs, JobFacts job, List<MatchReport.ComponentResult> comps) {
        List<Note> out = new ArrayList<>();
        if (job.expired()) {
            out.add(Note.of("blocker.closed"));
        }
        if (prefs != null && Boolean.TRUE.equals(prefs.needsVisaSponsorship()) && job.description() != null
                && VISA.matcher(job.description()).find()) {
            out.add(Note.of("blocker.visa"));
        }
        if (prefs != null && prefs.salaryMin() != null && prefs.salaryMin() > 0 && job.salaryMax() != null && job.salaryCurrency() != null
                && job.salaryCurrency().equalsIgnoreCase(prefs.salaryCurrency() == null ? "" : prefs.salaryCurrency())
                && job.salaryMax() < prefs.salaryMin()) {
            out.add(Note.of("blocker.salaryBelow", "max", job.salaryMax(), "min", prefs.salaryMin(), "currency", job.salaryCurrency()));
        }
        boolean relocate = prefs != null && prefs.willingToRelocate();
        comps.stream().filter(c -> c.key().equals("location") && c.known() && c.score() <= 0.3).findFirst().ifPresent(c -> {
            if (!relocate && job.remote() != RemoteMode.REMOTE) {
                out.add(Note.of("blocker.relocation"));
            }
        });
        return out;
    }

    private List<Note> adjustments(List<MatchReport.SkillResult> required, boolean reviewed) {
        List<Note> out = new ArrayList<>();
        if (!reviewed) {
            out.add(Note.of("adjust.review"));
        }
        required.stream().filter(s -> !s.matched()).limit(3)
                .forEach(s -> out.add(Note.of("adjust.missingRequired", "skill", s.skill())));
        required.stream().filter(s -> s.matched() && "Listed in skills section".equals(s.evidence())).limit(3)
                .forEach(s -> out.add(Note.of("adjust.showInUse", "skill", s.skill())));
        return out;
    }

    private List<MatchReport.LearningItem> learning(List<MatchReport.SkillResult> req, List<MatchReport.SkillResult> pref) {
        List<MatchReport.LearningItem> out = new ArrayList<>();
        Comparator<MatchReport.SkillResult> byMentions = Comparator.comparingInt(MatchReport.SkillResult::mentions).reversed()
                .thenComparing(MatchReport.SkillResult::skill);
        req.stream().filter(s -> !s.matched()).sorted(byMentions)
                .forEach(s -> out.add(new MatchReport.LearningItem(s.skill(), "REQUIRED", s.mentions())));
        pref.stream().filter(s -> !s.matched()).sorted(byMentions)
                .forEach(s -> out.add(new MatchReport.LearningItem(s.skill(), "PREFERRED", s.mentions())));
        return out.stream().limit(5).toList();
    }

    private List<MatchReport.ExperienceHit> relevantExperience(ParsedCv cv, List<MatchReport.SkillResult> skills) {
        Set<String> wanted = new LinkedHashSet<>();
        skills.forEach(s -> wanted.add(s.skill().toLowerCase(Locale.ROOT)));
        record Scored(ParsedCv.Experience e, List<String> hit, String quote) {
        }
        List<Scored> scored = new ArrayList<>();
        for (ParsedCv.Experience e : cv.experience()) {
            Set<String> hits = new LinkedHashSet<>();
            String quote = null;
            for (String text : textsOf(e)) {
                List<String> inText = catalog.find(text).stream().map(h -> h.skill().canonical()).filter(n -> wanted.contains(n.toLowerCase(Locale.ROOT))).toList();
                if (!inText.isEmpty() && quote == null && !text.equals(e.title())) {
                    quote = text.length() > 220 ? text.substring(0, 219) + "…" : text;
                }
                hits.addAll(inText);
            }
            if (!hits.isEmpty()) {
                scored.add(new Scored(e, List.copyOf(hits), quote));
            }
        }
        return scored.stream().sorted(Comparator.comparingInt((Scored s) -> s.hit().size()).reversed()).limit(3)
                .map(s -> new MatchReport.ExperienceHit(s.e().title(), s.e().company(), s.e().start(), s.e().end(), s.e().current(),
                        s.quote(), s.hit()))
                .toList();
    }

    // ---- helpers ----

    private Map<String, String> candidateSkills(ParsedCv cv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (ParsedCv.SkillEntry s : cv.skills()) {
            if (s.name() == null || s.name().isBlank()) {
                continue;
            }
            List<SkillCatalog.Hit> hits = catalog.find(s.name());
            String key = (hits.isEmpty() ? s.name() : hits.get(0).skill().canonical()).toLowerCase(Locale.ROOT);
            m.putIfAbsent(key, s.evidence() == null ? "Listed in skills section" : s.evidence());
        }
        return m;
    }

    /** CV content that describes the work. Name, email, phone and addresses are deliberately excluded. */
    private static String cvText(ParsedCv cv) {
        StringBuilder sb = new StringBuilder();
        if (cv.summary() != null) {
            sb.append(cv.summary()).append('\n');
        }
        cv.skills().forEach(s -> sb.append(s.name()).append('\n'));
        for (ParsedCv.Experience e : cv.experience()) {
            textsOf(e).forEach(t -> sb.append(t).append('\n'));
        }
        cv.education().forEach(e -> sb.append(e.degree() == null ? "" : e.degree()).append('\n'));
        cv.certifications().forEach(c -> sb.append(c).append('\n'));
        cv.projects().forEach(p -> sb.append(p).append('\n'));
        return sb.toString();
    }

    private static List<String> textsOf(ParsedCv.Experience e) {
        List<String> t = new ArrayList<>();
        if (e.title() != null) {
            t.add(e.title());
        }
        t.addAll(e.bullets());
        return t;
    }

    /** First "N years" that sits next to the word experience, so "5 years old company" is not read as a requirement. */
    static Integer requiredYears(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = YEARS.matcher(text);
        while (m.find()) {
            int from = Math.max(0, m.start() - 60);
            int to = Math.min(text.length(), m.end() + 60);
            if (EXPERIENCE_WORD.matcher(text.substring(from, to)).find()) {
                int n = Integer.parseInt(m.group(1));
                if (n >= 1 && n <= 25) {
                    return n;
                }
            }
        }
        return null;
    }

    static String seniorityOf(String title) {
        if (title == null) {
            return null;
        }
        String t = title.toLowerCase(Locale.ROOT);
        if (Pattern.compile("\\b(?:lead|principal|staff|head of|architect|architecte|manager|responsable)\\b").matcher(t).find()) {
            return "lead";
        }
        if (Pattern.compile("\\b(?:senior|sr\\.?|confirmé|confirme|expérimenté|experimente|expert)\\b").matcher(t).find()) {
            return "senior";
        }
        if (Pattern.compile("\\b(?:junior|jr\\.?|entry[- ]level|graduate|débutant|debutant|stagiaire|intern|trainee|alternance)\\b").matcher(t).find()) {
            return "junior";
        }
        if (Pattern.compile("\\b(?:mid|intermediate|intermédiaire)\\b").matcher(t).find()) {
            return "mid";
        }
        return null;
    }

    static String levelFromYears(double years) {
        return years < 2 ? "junior" : years < 5 ? "mid" : years < 9 ? "senior" : "lead";
    }

    private static int rank(String level) {
        return switch (level) {
            case "junior" -> 0;
            case "mid" -> 1;
            case "senior" -> 2;
            default -> 3;
        };
    }

    static Set<String> titleTokens(String title) {
        if (title == null) {
            return Set.of();
        }
        String t = GENDER.matcher(title.toLowerCase(Locale.ROOT)).replaceAll(" ");
        Set<String> out = new LinkedHashSet<>();
        for (String raw : t.split("[^\\p{L}\\p{N}+#-]+")) {
            String w = TITLE_SYNONYMS.getOrDefault(raw, raw);
            if (w.length() >= 2 && !LEVEL_WORDS.contains(w) && !Set.of("de", "du", "des", "et", "of", "the", "and", "for", "en", "at", "chez").contains(w)) {
                out.add(w);
            }
        }
        return out;
    }

    static String contractCategory(String type) {
        if (type == null) {
            return null;
        }
        String t = type.toLowerCase(Locale.ROOT);
        if (t.matches(".*\\b(?:cdi|permanent|unbefristet)\\b.*")) {
            return "permanent";
        }
        if (t.matches(".*\\b(?:cdd|fixed[ _-]?term|temporary|befristet)\\b.*")) {
            return "fixed_term";
        }
        if (t.matches(".*(?:freelance|independent|ind[ée]pendant).*")) {
            return "freelance";
        }
        if (t.matches(".*\\b(?:contract|contractor|contrat)\\b.*")) {
            return "contract";
        }
        if (t.matches(".*(?:intern|stage|stagiaire).*")) {
            return "internship";
        }
        if (t.matches(".*(?:apprentice|alternance).*")) {
            return "apprenticeship";
        }
        return null; // "full_time" alone says nothing about the contract type
    }
}
