package ai.jobmatcher.jobs;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ai.jobmatcher.cv.CvParser;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores normalised postings: updates a posting already known by (source, external id), otherwise creates it and
 * links it to an earlier identical role from another source as a duplicate. Re-seeing an expired posting reopens it.
 */
@Service
public class OfferIngestService {

    public record Result(int created, int updated, int duplicates, int skipped) {
        public static Result empty() {
            return new Result(0, 0, 0, 0);
        }

        public Result plus(Result o) {
            return new Result(created + o.created, updated + o.updated, duplicates + o.duplicates, skipped + o.skipped);
        }
    }

    static final int MAX_DESCRIPTION = 50_000;

    private final JobOfferRepository offers;

    OfferIngestService(JobOfferRepository offers) {
        this.offers = offers;
    }

    /** @param ownerUserId null for the shared catalog, or the user who submitted the URL. */
    @Transactional
    public Result ingest(String sourceKey, UUID ownerUserId, List<RawJob> raws) {
        int created = 0;
        int updated = 0;
        int duplicates = 0;
        int skipped = 0;
        Instant now = Instant.now();
        for (RawJob raw : raws) {
            if (!valid(raw)) {
                skipped++;
                continue;
            }
            JobOffer existing = offers.findBySourceKeyAndExternalIdAndOwnerUserId(sourceKey, cut(raw.externalId(), 300), ownerUserId)
                    .orElse(null);
            if (existing != null) {
                apply(existing, raw);
                existing.setLastSeenAt(now);
                existing.setStatus(JobOffer.Status.ACTIVE);
                offers.save(existing);
                updated++;
                continue;
            }
            JobOffer o = new JobOffer();
            o.setSourceKey(sourceKey);
            o.setExternalId(cut(raw.externalId(), 300));
            o.setOwnerUserId(ownerUserId);
            o.setFirstSeenAt(now);
            o.setLastSeenAt(now);
            apply(o, raw);
            List<JobOffer> canon = ownerUserId == null
                    ? offers.findCatalogCanonical(o.getFingerprint(), PageRequest.of(0, 1))
                    : offers.findCanonicalForOwner(o.getFingerprint(), ownerUserId, PageRequest.of(0, 1));
            if (!canon.isEmpty()) {
                o.setDuplicateOf(canon.get(0).getId());
                duplicates++;
            } else {
                created++;
            }
            offers.save(o);
        }
        return new Result(created, updated, duplicates, skipped);
    }

    private static boolean valid(RawJob r) {
        return r != null && notBlank(r.externalId()) && notBlank(r.title()) && notBlank(r.description()) && httpUrl(r.url());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean httpUrl(String u) {
        return u != null && (u.startsWith("https://") || u.startsWith("http://")) && u.length() <= 2000;
    }

    private static void apply(JobOffer o, RawJob r) {
        o.setTitle(cut(r.title().trim(), 300));
        o.setCompany(cut(trimOrNull(r.company()), 300));
        o.setLocationText(cut(trimOrNull(r.location()), 300));
        o.setCountry(r.country() != null && r.country().matches("[A-Z]{2}") ? r.country() : null);
        o.setRemoteMode(r.remote() == null ? RemoteMode.UNKNOWN : r.remote());
        o.setDescription(cut(r.description().trim(), MAX_DESCRIPTION));
        o.setEmploymentType(cut(trimOrNull(r.employmentType()), 30));
        boolean salaryOk = r.salaryMin() != null && r.salaryMax() != null && r.salaryMin() >= 0 && r.salaryMin() <= r.salaryMax()
                && r.salaryCurrency() != null && r.salaryCurrency().matches("[A-Z]{3}");
        o.setSalaryMin(salaryOk ? r.salaryMin() : null);
        o.setSalaryMax(salaryOk ? r.salaryMax() : null);
        o.setSalaryCurrency(salaryOk ? r.salaryCurrency() : null);
        o.setPostedAt(r.postedAt());
        o.setValidThrough(r.validThrough());
        o.setJobUrl(r.url());
        o.setApplyUrl(httpUrl(r.applyUrl()) ? r.applyUrl() : r.url());
        o.setLanguage(CvParser.detectLanguage(r.description()));
        o.setFingerprint(Fingerprint.of(r.title(), r.company(), r.location()));
    }

    private static String trimOrNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
