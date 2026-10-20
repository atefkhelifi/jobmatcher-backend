package ai.jobmatcher.jobs;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import ai.jobmatcher.fetch.SafeFetcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Detects offers that are no longer open. An offer expires when its stated deadline has passed, when it has not been
 * seen in any search for a long time, or when its public page answers 404 or 410. Network errors never expire an
 * offer: a site being down is not evidence that the job is gone.
 */
@Service
public class OfferVerifier {

    private static final Logger log = LoggerFactory.getLogger(OfferVerifier.class);

    static final Duration STALE_AFTER = Duration.ofDays(30);
    static final Duration RECHECK_AFTER = Duration.ofDays(3);

    private final JobOfferRepository offers;
    private final SafeFetcher fetcher;
    private final TransactionTemplate tx;
    private final int batchSize;

    OfferVerifier(JobOfferRepository offers, SafeFetcher fetcher, TransactionTemplate tx,
            @Value("${app.jobs.verify-batch:25}") int batchSize) {
        this.offers = offers;
        this.fetcher = fetcher;
        this.tx = tx;
        this.batchSize = batchSize;
    }

    /** Pure rule for the offline reasons, so it can be tested without a network. */
    static String offlineReason(JobOffer o, Instant now) {
        if (o.getValidThrough() != null && o.getValidThrough().isBefore(now)) {
            return "deadline passed";
        }
        if (o.getLastSeenAt() != null && o.getLastSeenAt().isBefore(now.minus(STALE_AFTER))) {
            return "not seen for " + STALE_AFTER.toDays() + " days";
        }
        return null;
    }

    /** @return number of offers marked expired in this batch */
    public int verifyBatch() {
        Instant now = Instant.now();
        List<JobOffer> due = offers.findDueForVerification(now.minus(RECHECK_AFTER), PageRequest.of(0, batchSize));
        int expired = 0;
        for (JobOffer o : due) {
            String reason = offlineReason(o, now);
            Boolean checkedOnline = null;
            if (reason == null) {
                try {
                    int status = fetcher.get(o.getJobUrl(), "text/html,application/json", true).status();
                    if (status == 404 || status == 410) {
                        reason = "page returned " + status;
                    }
                    checkedOnline = status < 500;
                } catch (SafeFetcher.FetchException e) {
                    log.debug("Could not verify {}: {}", o.getId(), e.getMessage());
                }
            }
            final String why = reason;
            final boolean verified = reason != null || Boolean.TRUE.equals(checkedOnline);
            tx.executeWithoutResult(s -> offers.findById(o.getId()).ifPresent(fresh -> {
                if (why != null) {
                    fresh.setStatus(JobOffer.Status.EXPIRED);
                }
                if (verified) {
                    fresh.setLastVerifiedAt(Instant.now());
                } else {
                    // Retry later, but not on every run.
                    fresh.setLastVerifiedAt(Instant.now().minus(RECHECK_AFTER).plus(Duration.ofHours(6)));
                }
                offers.save(fresh);
            }));
            if (why != null) {
                expired++;
            }
        }
        return expired;
    }
}
