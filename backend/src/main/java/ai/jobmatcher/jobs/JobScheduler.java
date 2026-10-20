package ai.jobmatcher.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background work: automatic saved searches and expired-offer detection. It assumes a single API instance; run
 * more than one and add a distributed lock (for example ShedLock) first.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.jobs.scheduling-enabled", havingValue = "true", matchIfMissing = true)
class JobScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

    private final SearchService searches;
    private final OfferVerifier verifier;
    private final RetentionService retention;

    JobScheduler(SearchService searches, OfferVerifier verifier, RetentionService retention) {
        this.searches = searches;
        this.verifier = verifier;
        this.retention = retention;
    }

    @Scheduled(cron = "${app.retention.cron:0 30 3 * * *}")
    void purgeOldOffers() {
        try {
            retention.purgeExpiredOffers();
        } catch (RuntimeException e) {
            log.warn("Retention run failed: {}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "${app.jobs.search-poll:PT15M}")
    void runDueSearches() {
        try {
            int ran = searches.runDue();
            if (ran > 0) {
                log.info("Ran {} scheduled search(es)", ran);
            }
        } catch (RuntimeException e) {
            log.warn("Scheduled searches failed: {}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(initialDelayString = "PT10M", fixedDelayString = "${app.jobs.verify-poll:PT6H}")
    void verifyOffers() {
        try {
            int expired = verifier.verifyBatch();
            if (expired > 0) {
                log.info("Marked {} offer(s) as expired", expired);
            }
        } catch (RuntimeException e) {
            log.warn("Offer verification failed: {}", e.getClass().getSimpleName());
        }
    }
}
