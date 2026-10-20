package ai.jobmatcher.matching;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import ai.jobmatcher.common.FieldCrypto;
import ai.jobmatcher.common.NotFoundException;
import ai.jobmatcher.cv.CvService;
import ai.jobmatcher.jobs.JobOffer;
import ai.jobmatcher.jobs.JobOfferRepository;
import ai.jobmatcher.profile.ProfileService;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Computes and stores match results. A stored result is reused only while the CV, the preferences and the offer are
 * unchanged, so what the user sees always reflects their current data.
 */
@Service
public class MatchService {

    static final int MAX_JOBS_PER_RUN = 2000;
    private static final int PAGE = 200;

    public record Brief(Integer score, int coverage) {
    }

    public record View(UUID jobId, UUID cvId, MatchReport report, Map<String, Integer> weights) {
    }

    public record RunSummary(int total, int computed, int reused, int scored) {
    }

    private final JobMatchRepository matches;
    private final JobOfferRepository offers;
    private final CvService cvs;
    private final ProfileService profiles;
    private final MatchScorer scorer;
    private final MatchWeights weights;
    private final FieldCrypto crypto;
    private final JsonMapper json;

    MatchService(JobMatchRepository matches, JobOfferRepository offers, CvService cvs, ProfileService profiles,
            MatchScorer scorer, MatchWeights weights, FieldCrypto crypto, JsonMapper json) {
        this.matches = matches;
        this.offers = offers;
        this.cvs = cvs;
        this.profiles = profiles;
        this.scorer = scorer;
        this.weights = weights;
        this.crypto = crypto;
        this.json = json;
    }

    /** Throws NotFoundException unless the CV belongs to the user. */
    public void requireCv(UUID userId, UUID cvId) {
        cvs.get(userId, cvId);
    }

    @Transactional
    public View match(UUID userId, UUID cvId, UUID jobId) {
        Context ctx = context(userId, cvId);
        JobOffer job = offers.findById(jobId)
                .filter(o -> o.getOwnerUserId() == null || o.getOwnerUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Job not found."));
        JobMatch existing = matches.findByCvIdAndJobIdAndUserId(cvId, jobId, userId).orElse(null);
        JobMatch m = fresh(existing, ctx, job) ? existing : compute(existing, ctx, job);
        return new View(jobId, cvId, read(m), weights.asMap());
    }

    @Transactional
    public RunSummary runAll(UUID userId, UUID cvId) {
        Context ctx = context(userId, cvId);
        Map<UUID, JobMatch> byJob = new HashMap<>();
        matches.findByCvIdAndUserId(cvId, userId).forEach(m -> byJob.put(m.getJobId(), m));
        int total = 0;
        int computed = 0;
        int scored = 0;
        for (int page = 0; total < MAX_JOBS_PER_RUN; page++) {
            List<JobOffer> batch = offers.findVisibleActive(userId, PageRequest.of(page, PAGE));
            if (batch.isEmpty()) {
                break;
            }
            for (JobOffer job : batch) {
                total++;
                JobMatch existing = byJob.get(job.getId());
                JobMatch m = fresh(existing, ctx, job) ? existing : compute(existing, ctx, job);
                if (m != existing) {
                    computed++;
                }
                if (m.getScore() != null) {
                    scored++;
                }
            }
            if (batch.size() < PAGE) {
                break;
            }
        }
        return new RunSummary(total, computed, total - computed, scored);
    }

    /** Scores that are current for the given jobs, keyed by job id. Outdated results are left out. */
    @Transactional(readOnly = true)
    public Map<UUID, Brief> briefs(UUID userId, UUID cvId, Collection<JobOffer> jobs) {
        if (jobs.isEmpty()) {
            return Map.of();
        }
        Context ctx = context(userId, cvId);
        Map<UUID, JobOffer> byId = new HashMap<>();
        jobs.forEach(j -> byId.put(j.getId(), j));
        Map<UUID, Brief> out = new HashMap<>();
        for (JobMatch m : matches.findByCvIdAndUserIdAndJobIdIn(cvId, userId, byId.keySet())) {
            if (fresh(m, ctx, byId.get(m.getJobId()))) {
                out.put(m.getJobId(), new Brief(m.getScore(), m.getCoverage()));
            }
        }
        return out;
    }

    // ---- internals ----

    private record Context(UUID userId, UUID cvId, CvService.Detail cv, ProfileService.Preferences prefs) {
    }

    private Context context(UUID userId, UUID cvId) {
        return new Context(userId, cvId, cvs.get(userId, cvId), profiles.get(userId));
    }

    private boolean fresh(JobMatch m, Context ctx, JobOffer job) {
        return m != null && m.getStamp().equals(stamp(ctx, job));
    }

    private JobMatch compute(JobMatch existing, Context ctx, JobOffer job) {
        MatchReport report = scorer.score(ctx.cv().parsed(), ctx.prefs(), facts(job), ctx.cv().summary().reviewed());
        JobMatch m = existing != null ? existing : new JobMatch();
        if (existing == null) {
            m.setUserId(ctx.userId());
            m.setCvId(ctx.cvId());
            m.setJobId(job.getId());
        }
        m.setScore(report.score());
        m.setCoverage(report.coverage());
        m.setStamp(stamp(ctx, job));
        m.setReportJson(crypto.encrypt(json.writeValueAsString(report)));
        return matches.save(m);
    }

    private MatchReport read(JobMatch m) {
        return json.readValue(crypto.decrypt(m.getReportJson()), MatchReport.class);
    }

    static MatchScorer.JobFacts facts(JobOffer j) {
        return new MatchScorer.JobFacts(j.getTitle(), j.getDescription(), j.getLocationText(), j.getCountry(), j.getRemoteMode(),
                j.getEmploymentType(), j.getSalaryMin(), j.getSalaryMax(), j.getSalaryCurrency(), j.getStatus() == JobOffer.Status.EXPIRED);
    }

    /** Changes whenever the CV, the preferences, the offer's content, or the weighting change. */
    private String stamp(Context ctx, JobOffer job) {
        int jobHash = Objects.hash(job.getTitle(), job.getDescription(), job.getLocationText(), job.getCountry(), job.getRemoteMode(),
                job.getEmploymentType(), job.getSalaryMin(), job.getSalaryMax(), job.getSalaryCurrency(), job.getStatus());
        return ctx.cv().summary().updatedAt().toEpochMilli() + ":" + Integer.toHexString(ctx.prefs().hashCode()) + ":"
                + Integer.toHexString(jobHash) + ":" + Integer.toHexString(weights.hashCode());
    }
}
