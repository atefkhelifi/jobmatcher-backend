package ai.jobmatcher.jobs;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Data retention for the shared catalogue: offers that have been expired for a long time are deleted, unless a user still
 * relies on them (a letter or email was written for them, or an application points at them). Applications keep their own
 * snapshot of the role, so nothing a user kept is lost.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final JdbcTemplate jdbc;
    private final Duration expiredFor;

    RetentionService(JdbcTemplate jdbc, @Value("${app.retention.expired-offers-days:180}") int days) {
        this.jdbc = jdbc;
        this.expiredFor = Duration.ofDays(Math.max(30, days));
    }

    /** @return the number of offers removed */
    @Transactional
    public int purgeExpiredOffers() {
        return purgeExpiredOffers(Instant.now());
    }

    int purgeExpiredOffers(Instant now) {
        int removed = jdbc.update("""
                delete from job_offer o
                where o.status = 'EXPIRED' and o.last_seen_at < ?
                  and not exists (select 1 from generated_document d where d.job_id = o.id)
                  and not exists (select 1 from job_application a where a.job_id = o.id)
                """, java.sql.Timestamp.from(now.minus(expiredFor)));
        if (removed > 0) {
            log.info("Retention removed {} expired offers older than {} days", removed, expiredFor.toDays());
        }
        return removed;
    }
}
