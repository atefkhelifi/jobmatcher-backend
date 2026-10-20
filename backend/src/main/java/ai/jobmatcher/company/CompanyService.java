package ai.jobmatcher.company;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import ai.jobmatcher.applications.ApplicationRepository;
import ai.jobmatcher.applications.JobApplication;
import ai.jobmatcher.common.NotFoundException;
import ai.jobmatcher.jobs.Fingerprint;
import ai.jobmatcher.jobs.JobOffer;
import ai.jobmatcher.jobs.JobOfferRepository;
import ai.jobmatcher.jobs.JobQualityAnalyzer;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the platform itself knows about an employer, and nothing it would have to guess: the other open offers it has seen under
 * that company name, where they were published, how often the wording of those ads raised a warning, and the user's own history
 * with the company. Every figure is a count of offers or applications the user can open; there are no scraped reviews, ratings
 * or financial claims, because those could not be cited.
 */
@Service
public class CompanyService {

    static final int MAX_OFFERS = 10;
    private static final int SCAN = 200;

    public record OfferRef(UUID id, String title, String location, String sourceKey, Instant firstSeenAt) {
    }

    public record History(int applications, Map<String, Integer> byStatus) {
    }

    public record Profile(String company, boolean named, int openOffers, Map<String, Integer> bySource, Map<String, Integer> byCountry,
            Instant firstSeenAt, int offersWithWarnings, List<OfferRef> offers, History yourHistory) {
    }

    private final JobOfferRepository offers;
    private final ApplicationRepository apps;
    private final JobQualityAnalyzer quality;

    CompanyService(JobOfferRepository offers, ApplicationRepository apps, JobQualityAnalyzer quality) {
        this.offers = offers;
        this.apps = apps;
        this.quality = quality;
    }

    @Transactional(readOnly = true)
    public Profile forJob(UUID userId, UUID jobId) {
        JobOffer job = offers.findById(jobId).filter(o -> o.getOwnerUserId() == null || o.getOwnerUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Job not found."));
        String name = job.getCompany();
        if (name == null || name.isBlank()) {
            return new Profile(null, false, 0, Map.of(), Map.of(), null, 0, List.of(), new History(0, Map.of()));
        }
        String key = Fingerprint.company(name);
        // Spelling varies ("Acme", "ACME GmbH"), so the database narrows by the first letters of the name as written (accents kept)
        // and Java then compares the normalised names exactly.
        String clean = name.strip().replace("%", "").replace("_", "").replace("\\", "");
        String prefix = clean.substring(0, Math.min(clean.length(), 4));
        List<JobOffer> same = offers.findCatalogByCompanyContaining(prefix, PageRequest.of(0, SCAN)).stream()
                .filter(o -> Fingerprint.company(o.getCompany()).equals(key)).toList();

        Map<String, Integer> bySource = new TreeMap<>();
        Map<String, Integer> byCountry = new TreeMap<>();
        Instant first = null;
        int warned = 0;
        for (JobOffer o : same) {
            bySource.merge(o.getSourceKey(), 1, Integer::sum);
            if (o.getCountry() != null) {
                byCountry.merge(o.getCountry().toUpperCase(java.util.Locale.ROOT), 1, Integer::sum);
            }
            if (o.getFirstSeenAt() != null && (first == null || o.getFirstSeenAt().isBefore(first))) {
                first = o.getFirstSeenAt();
            }
            if (quality.analyze(o.getTitle(), o.getCompany(), o.getDescription()).stream()
                    .anyMatch(w -> w.severity() == JobQualityAnalyzer.Severity.CAUTION)) {
                warned++;
            }
        }
        List<OfferRef> refs = same.stream().limit(MAX_OFFERS)
                .map(o -> new OfferRef(o.getId(), o.getTitle(), o.getLocationText(), o.getSourceKey(), o.getFirstSeenAt())).toList();

        Map<String, Integer> byStatus = new TreeMap<>();
        int mine = 0;
        for (JobApplication a : apps.findByUserIdOrderByUpdatedAtDesc(userId)) {
            if (a.getCompany() != null && Fingerprint.company(a.getCompany()).equals(key)) {
                mine++;
                byStatus.merge(a.getStatus().name(), 1, Integer::sum);
            }
        }
        return new Profile(name, true, same.size(), bySource, byCountry, first, warned, refs, new History(mine, byStatus));
    }

}
