package ai.jobmatcher.comparison;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import ai.jobmatcher.common.InvalidInputException;
import ai.jobmatcher.common.NotFoundException;
import ai.jobmatcher.cv.CvService;
import ai.jobmatcher.cv.ParsedCv;
import ai.jobmatcher.cv.SkillCatalog;
import ai.jobmatcher.jobs.JobOffer;
import ai.jobmatcher.jobs.JobOfferRepository;
import ai.jobmatcher.matching.JobSkillExtractor;
import ai.jobmatcher.matching.MatchService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Puts two versions of a CV side by side: how readable each is, how many of a chosen job's skills each shows, how well each
 * matches that job, and where the two disagree on facts (employers, dates, degrees). The service reports disagreements and
 * never decides which version is right; both are the user's own text.
 */
@Service
public class CvComparisonService {

    private static final Pattern DIGIT = Pattern.compile("\\d");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}'’+#.-]*");

    public record Readability(int words, int bullets, double averageBulletWords, double quantifiedBulletShare, int longBullets,
            boolean hasSummary, boolean hasSkills, boolean hasEducation, int experienceEntries) {
    }

    public record Coverage(int required, int requiredCovered, int preferred, int preferredCovered, List<String> missingRequired,
            List<String> missingPreferred) {
    }

    public record Side(UUID cvId, String label, Readability readability, Coverage coverage, Integer matchScore) {
    }

    /** A fact on which the two versions differ. {@code a} and {@code b} are the two values, null when absent from that version. */
    public record Difference(String code, String subject, String a, String b) {
    }

    public record JobRef(UUID id, String title, String company) {
    }

    public record Comparison(Side a, Side b, JobRef job, List<Difference> differences, List<String> skillsOnlyInA, List<String> skillsOnlyInB) {
    }

    static final int LONG_BULLET_WORDS = 30;

    private final CvService cvs;
    private final JobOfferRepository offers;
    private final JobSkillExtractor extractor;
    private final SkillCatalog catalog;
    private final MatchService matching;

    CvComparisonService(CvService cvs, JobOfferRepository offers, JobSkillExtractor extractor, SkillCatalog catalog, MatchService matching) {
        this.cvs = cvs;
        this.offers = offers;
        this.extractor = extractor;
        this.catalog = catalog;
        this.matching = matching;
    }

    @Transactional
    public Comparison compare(UUID userId, UUID cvA, UUID cvB, UUID jobId) {
        if (cvA.equals(cvB)) {
            throw new InvalidInputException("Choose two different CVs to compare.");
        }
        CvService.Detail a = cvs.get(userId, cvA);
        CvService.Detail b = cvs.get(userId, cvB);
        JobOffer job = jobId == null ? null : offers.findById(jobId).filter(o -> o.getOwnerUserId() == null || o.getOwnerUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Job not found."));
        List<JobSkillExtractor.JobSkill> wanted = job == null ? List.of() : extractor.extract(job.getTitle(), job.getDescription());

        Set<String> skillsA = skills(a);
        Set<String> skillsB = skills(b);
        List<String> onlyA = new ArrayList<>(skillsA);
        onlyA.removeIf(s -> containsIgnoreCase(skillsB, s));
        List<String> onlyB = new ArrayList<>(skillsB);
        onlyB.removeIf(s -> containsIgnoreCase(skillsA, s));

        return new Comparison(side(userId, a, skillsA, wanted, job), side(userId, b, skillsB, wanted, job),
                job == null ? null : new JobRef(job.getId(), job.getTitle(), job.getCompany()), differences(parsed(a), parsed(b)), onlyA, onlyB);
    }

    private Side side(UUID userId, CvService.Detail cv, Set<String> skills, List<JobSkillExtractor.JobSkill> wanted, JobOffer job) {
        Coverage coverage = null;
        Integer score = null;
        if (job != null) {
            coverage = coverage(skills, wanted);
            score = matching.match(userId, cv.summary().id(), job.getId()).report().score();
        }
        return new Side(cv.summary().id(), cv.summary().label(), readability(parsed(cv)), coverage, score);
    }

    static Readability readability(ParsedCv p) {
        int bullets = 0;
        int words = 0;
        int quantified = 0;
        int longBullets = 0;
        int totalBulletWords = 0;
        for (ParsedCv.Experience e : nz(p.experience())) {
            for (String bullet : nz(e.bullets())) {
                int n = count(bullet);
                bullets++;
                totalBulletWords += n;
                if (DIGIT.matcher(bullet).find()) {
                    quantified++;
                }
                if (n > LONG_BULLET_WORDS) {
                    longBullets++;
                }
            }
        }
        words += count(p.summary());
        words += totalBulletWords;
        return new Readability(words, bullets, bullets == 0 ? 0 : round(totalBulletWords / (double) bullets),
                bullets == 0 ? 0 : round(quantified * 100.0 / bullets), longBullets, p.summary() != null && !p.summary().isBlank(),
                !nz(p.skills()).isEmpty(), !nz(p.education()).isEmpty(), nz(p.experience()).size());
    }

    private Coverage coverage(Set<String> cvSkills, List<JobSkillExtractor.JobSkill> wanted) {
        List<String> missReq = new ArrayList<>();
        List<String> missPref = new ArrayList<>();
        int req = 0;
        int reqOk = 0;
        int pref = 0;
        int prefOk = 0;
        for (JobSkillExtractor.JobSkill s : wanted) {
            boolean has = containsIgnoreCase(cvSkills, s.skill());
            if (s.level() == JobSkillExtractor.Level.REQUIRED) {
                req++;
                if (has) {
                    reqOk++;
                } else {
                    missReq.add(s.skill());
                }
            } else {
                // A skill the ad only mentions is treated as preferred: it is not claimed to be mandatory.
                pref++;
                if (has) {
                    prefOk++;
                } else {
                    missPref.add(s.skill());
                }
            }
        }
        return new Coverage(req, reqOk, pref, prefOk, missReq, missPref);
    }

    /** Skills the user listed plus skills named in the CV text, by canonical name where the catalogue knows it. */
    private Set<String> skills(CvService.Detail cv) {
        Set<String> out = new LinkedHashSet<>();
        for (ParsedCv.SkillEntry s : nz(parsed(cv).skills())) {
            if (s.name() != null && !s.name().isBlank()) {
                SkillCatalog.Skill known = catalog.lookup(s.name());
                out.add(known == null ? s.name().trim() : known.canonical());
            }
        }
        if (cv.text() != null) {
            catalog.find(cv.text()).forEach(h -> out.add(h.skill().canonical()));
        }
        return out;
    }

    static List<Difference> differences(ParsedCv a, ParsedCv b) {
        List<Difference> out = new ArrayList<>();
        compareScalar(out, "NAME", "name", a.fullName(), b.fullName());
        compareScalar(out, "EMAIL", "email", a.email(), b.email());
        compareScalar(out, "PHONE", "phone", a.phone(), b.phone());

        Map<String, ParsedCv.Experience> expA = byEmployer(a);
        Map<String, ParsedCv.Experience> expB = byEmployer(b);
        for (var e : expA.entrySet()) {
            ParsedCv.Experience x = e.getValue();
            ParsedCv.Experience y = expB.get(e.getKey());
            String subject = label(x);
            if (y == null) {
                out.add(new Difference("EXPERIENCE_ONLY_IN_A", subject, period(x), null));
                continue;
            }
            if (!Objects.equals(x.start(), y.start()) || !Objects.equals(x.end(), y.end()) || x.current() != y.current()) {
                out.add(new Difference("EXPERIENCE_DATES_DIFFER", subject, period(x), period(y)));
            }
            if (!norm(x.title()).equals(norm(y.title())) && x.title() != null && y.title() != null) {
                out.add(new Difference("EXPERIENCE_TITLE_DIFFERS", x.company(), x.title(), y.title()));
            }
        }
        for (var e : expB.entrySet()) {
            if (!expA.containsKey(e.getKey())) {
                out.add(new Difference("EXPERIENCE_ONLY_IN_B", label(e.getValue()), null, period(e.getValue())));
            }
        }

        Map<String, ParsedCv.Education> eduA = byInstitution(a);
        Map<String, ParsedCv.Education> eduB = byInstitution(b);
        for (var e : eduA.entrySet()) {
            ParsedCv.Education x = e.getValue();
            ParsedCv.Education y = eduB.get(e.getKey());
            if (y == null) {
                out.add(new Difference("EDUCATION_ONLY_IN_A", x.institution(), x.degree(), null));
            } else if (!norm(x.degree()).equals(norm(y.degree())) || !Objects.equals(x.end(), y.end())) {
                out.add(new Difference("EDUCATION_DIFFERS", x.institution(), x.degree() + " " + nzs(x.end()), y.degree() + " " + nzs(y.end())));
            }
        }
        for (var e : eduB.entrySet()) {
            if (!eduA.containsKey(e.getKey())) {
                out.add(new Difference("EDUCATION_ONLY_IN_B", e.getValue().institution(), null, e.getValue().degree()));
            }
        }
        if (a.yearsOfExperience() != null && b.yearsOfExperience() != null && Math.abs(a.yearsOfExperience() - b.yearsOfExperience()) >= 1.0) {
            out.add(new Difference("YEARS_DIFFER", "yearsOfExperience", String.valueOf(a.yearsOfExperience()), String.valueOf(b.yearsOfExperience())));
        }
        return out;
    }

    private static void compareScalar(List<Difference> out, String code, String subject, String a, String b) {
        if (a != null && b != null && !norm(a).equals(norm(b))) {
            out.add(new Difference(code + "_DIFFERS", subject, a, b));
        }
    }

    private static Map<String, ParsedCv.Experience> byEmployer(ParsedCv p) {
        // Matched by employer, so a changed date is one difference rather than one job removed and another added. A second stint
        // at the same employer is matched with the second stint in the other version.
        Map<String, ParsedCv.Experience> m = new LinkedHashMap<>();
        Map<String, Integer> seen = new java.util.HashMap<>();
        for (ParsedCv.Experience e : nz(p.experience())) {
            if (e.company() != null && !e.company().isBlank()) {
                String company = norm(e.company());
                m.put(company + "#" + seen.merge(company, 1, Integer::sum), e);
            }
        }
        return m;
    }

    private static Map<String, ParsedCv.Education> byInstitution(ParsedCv p) {
        Map<String, ParsedCv.Education> m = new LinkedHashMap<>();
        for (ParsedCv.Education e : nz(p.education())) {
            if (e.institution() != null && !e.institution().isBlank()) {
                m.putIfAbsent(norm(e.institution()), e);
            }
        }
        return m;
    }

    private static String label(ParsedCv.Experience e) {
        return (e.title() == null ? "" : e.title() + " – ") + e.company();
    }

    private static String period(ParsedCv.Experience e) {
        return nzs(e.start()) + " → " + (e.current() ? "present" : nzs(e.end()));
    }

    private static ParsedCv parsed(CvService.Detail d) {
        return d.parsed() == null ? ParsedCv.empty() : d.parsed();
    }

    private static boolean containsIgnoreCase(Set<String> set, String s) {
        return set.stream().anyMatch(x -> x.equalsIgnoreCase(s));
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private static String nzs(String s) {
        return s == null ? "" : s;
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }

    private static int count(String s) {
        if (s == null) {
            return 0;
        }
        int n = 0;
        var m = WORD.matcher(s);
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
