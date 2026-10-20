package ai.jobmatcher.jobs;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import ai.jobmatcher.common.InvalidInputException;
import ai.jobmatcher.common.NotFoundException;
import ai.jobmatcher.fetch.SafeFetcher;
import ai.jobmatcher.matching.MatchService;
import ai.jobmatcher.matching.JobMatch;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobQueryService {

    public record Filter(String q, String location, RemoteMode remote, String source, Integer postedWithinDays,
            Integer minSalary, boolean includeUnknownSalary, boolean includeExpired, String sort, int page, int size,
            UUID cvId, Integer minScore) {
    }

    public record Summary(UUID id, String title, String company, String location, String country, RemoteMode remoteMode,
            String employmentType, Integer salaryMin, Integer salaryMax, String salaryCurrency, Instant postedAt,
            Instant validThrough, String sourceKey, String sourceName, String url, String applyUrl, String status,
            String language, String snippet, boolean submittedByYou, Integer matchScore, Integer matchCoverage) {
    }

    public record Detail(Summary summary, String description, Instant firstSeenAt, Instant lastSeenAt, Instant lastVerifiedAt,
            String sourceTermsUrl, List<JobQualityAnalyzer.Warning> warnings) {
    }

    public record PageView(List<Summary> items, int page, int size, long total, int totalPages) {
    }

    private final JobOfferRepository offers;
    private final List<JobSourceAdapter> adapters;
    private final SafeFetcher fetcher;
    private final JobPostingParser parser;
    private final OfferIngestService ingest;
    private final MatchService matching;
    private final JobQualityAnalyzer quality;

    JobQueryService(JobOfferRepository offers, List<JobSourceAdapter> adapters, SafeFetcher fetcher, JobPostingParser parser,
            OfferIngestService ingest, MatchService matching, JobQualityAnalyzer quality) {
        this.quality = quality;
        this.matching = matching;
        this.offers = offers;
        this.adapters = adapters;
        this.fetcher = fetcher;
        this.parser = parser;
        this.ingest = ingest;
    }

    @Transactional(readOnly = true)
    public PageView search(UUID userId, Filter f) {
        String sortKey = f.sort() == null ? "newest" : f.sort();
        boolean byMatch = sortKey.equals("match");
        if ((byMatch || f.minScore() != null) && f.cvId() == null) {
            throw new InvalidInputException("Choose a CV to sort or filter by match score.");
        }
        if (f.cvId() != null) {
            matching.requireCv(userId, f.cvId());
        }
        Sort sort = switch (sortKey) {
            case "deadline" -> Sort.by(Sort.Order.asc("validThrough").nullsLast(), Sort.Order.desc("firstSeenAt"));
            case "salary" -> Sort.by(Sort.Order.desc("salaryMax").nullsLast(), Sort.Order.desc("firstSeenAt"));
            case "company" -> Sort.by(Sort.Order.asc("company").nullsLast(), Sort.Order.desc("firstSeenAt"));
            case "newest" -> Sort.by(Sort.Order.desc("postedAt").nullsLast(), Sort.Order.desc("firstSeenAt"));
            case "match" -> Sort.unsorted(); // ordered inside the specification, by the joined score
            default -> throw new InvalidInputException("Unknown sort. Use newest, deadline, salary, company or match.");
        };
        int size = Math.max(1, Math.min(f.size(), 50));
        Page<JobOffer> page = offers.findAll(spec(userId, f, byMatch), PageRequest.of(Math.max(0, f.page()), size, sort));
        Map<String, String> names = names();
        Map<UUID, MatchService.Brief> briefs = f.cvId() == null ? Map.of() : matching.briefs(userId, f.cvId(), page.getContent());
        return new PageView(page.getContent().stream().map(o -> summary(o, userId, names, briefs.get(o.getId()))).toList(),
                page.getNumber(), size, page.getTotalElements(), page.getTotalPages());
    }

    @Transactional(readOnly = true)
    public Detail get(UUID userId, UUID id) {
        JobOffer o = offers.findById(id).filter(x -> visible(x, userId)).orElseThrow(() -> new NotFoundException("Job not found."));
        Map<String, String> names = names();
        String terms = adapters.stream().filter(a -> a.key().equals(o.getSourceKey())).map(JobSourceAdapter::termsUrl).findFirst().orElse(null);
        return new Detail(summary(o, userId, names, null), o.getDescription(), o.getFirstSeenAt(), o.getLastSeenAt(), o.getLastVerifiedAt(), terms,
                quality.analyze(o.getTitle(), o.getCompany(), o.getDescription()));
    }

    /** Imports one job page the user points at. Only structured JobPosting data is read, and robots.txt is honoured. */
    public Detail importUrl(UUID userId, String url) {
        SafeFetcher.Response res;
        try {
            res = fetcher.get(url.trim(), "text/html,application/xhtml+xml", true);
        } catch (SafeFetcher.FetchException e) {
            throw new InvalidInputException(e.getMessage());
        }
        if (res.status() != 200) {
            throw new InvalidInputException("The page answered with HTTP " + res.status() + ".");
        }
        RawJob job = parser.parse(res.body(), res.finalUri()).orElseThrow(() -> new InvalidInputException(
                "No structured job posting (schema.org JobPosting) was found on that page. Many career sites only show jobs through scripts; open the offer on its own page or use a supported source."));
        ingest.ingest("url", userId, List.of(job));
        JobOffer stored = offers.findBySourceKeyAndExternalIdAndOwnerUserId("url", job.externalId().length() > 300 ? job.externalId().substring(0, 300) : job.externalId(), userId)
                .orElseThrow(() -> new InvalidInputException("The job posting was incomplete and could not be saved."));
        return get(userId, stored.getId());
    }

    private static boolean visible(JobOffer o, UUID userId) {
        return o.getOwnerUserId() == null || o.getOwnerUserId().equals(userId);
    }

    private Map<String, String> names() {
        Map<String, String> m = adapters.stream().collect(Collectors.toMap(JobSourceAdapter::key, JobSourceAdapter::displayName));
        m.put("url", "Submitted by you");
        return m;
    }

    private static Summary summary(JobOffer o, UUID userId, Map<String, String> names, MatchService.Brief brief) {
        String d = o.getDescription();
        String snippet = d.length() <= 240 ? d : d.substring(0, 240).replaceAll("\\s+\\S*$", "") + "…";
        return new Summary(o.getId(), o.getTitle(), o.getCompany(), o.getLocationText(), o.getCountry(), o.getRemoteMode(),
                o.getEmploymentType(), o.getSalaryMin(), o.getSalaryMax(), o.getSalaryCurrency(), o.getPostedAt(), o.getValidThrough(),
                o.getSourceKey(), names.getOrDefault(o.getSourceKey(), o.getSourceKey()), o.getJobUrl(), o.getApplyUrl(),
                o.getStatus().name(), o.getLanguage(), snippet.replace('\n', ' '), userId.equals(o.getOwnerUserId()),
                brief == null ? null : brief.score(), brief == null ? null : brief.coverage());
    }

    private static Specification<JobOffer> spec(UUID userId, Filter f, boolean byMatch) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (f.cvId() != null && (byMatch || f.minScore() != null)) {
                // Left join limited to this CV, so offers not scored yet still appear (last when sorting by score).
                Join<JobOffer, JobMatch> m = root.join("matches", JoinType.LEFT);
                m.on(cb.equal(m.get("cvId"), f.cvId()));
                if (f.minScore() != null) {
                    p.add(cb.greaterThanOrEqualTo(m.<Integer>get("score"), f.minScore()));
                }
                if (byMatch && !Long.class.equals(query.getResultType())) {
                    query.orderBy(cb.desc(cb.coalesce(m.<Integer>get("score"), -1)), cb.desc(cb.coalesce(root.<Instant>get("postedAt"), root.<Instant>get("firstSeenAt"))));
                }
            }
            p.add(cb.isNull(root.get("duplicateOf")));
            p.add(cb.or(cb.isNull(root.get("ownerUserId")), cb.equal(root.get("ownerUserId"), userId)));
            if (!f.includeExpired()) {
                p.add(cb.equal(root.get("status"), JobOffer.Status.ACTIVE));
            }
            if (notBlank(f.q())) {
                for (String word : f.q().trim().toLowerCase(Locale.ROOT).split("\\s+")) {
                    String like = "%" + escape(word) + "%";
                    p.add(cb.or(cb.like(cb.lower(root.get("title")), like, '\\'), cb.like(cb.lower(root.get("company")), like, '\\'),
                            cb.like(cb.lower(root.get("description")), like, '\\')));
                }
            }
            if (notBlank(f.location())) {
                p.add(cb.like(cb.lower(root.get("locationText")), "%" + escape(f.location().trim().toLowerCase(Locale.ROOT)) + "%", '\\'));
            }
            if (f.remote() != null) {
                p.add(cb.equal(root.get("remoteMode"), f.remote()));
            }
            if (notBlank(f.source())) {
                p.add(cb.equal(root.get("sourceKey"), f.source()));
            }
            if (f.postedWithinDays() != null && f.postedWithinDays() > 0) {
                p.add(cb.greaterThanOrEqualTo(root.<Instant>get("postedAt"), Instant.now().minus(Duration.ofDays(f.postedWithinDays()))));
            }
            if (f.minSalary() != null) {
                Predicate known = cb.greaterThanOrEqualTo(root.<Integer>get("salaryMax"), f.minSalary());
                p.add(f.includeUnknownSalary() ? cb.or(cb.isNull(root.get("salaryMax")), known) : known);
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
