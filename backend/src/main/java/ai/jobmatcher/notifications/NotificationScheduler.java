package ai.jobmatcher.notifications;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the notification jobs in the background; switched off together with the other schedulers in tests. */
@Component
@ConditionalOnProperty(name = "app.jobs.scheduling-enabled", havingValue = "true", matchIfMissing = true)
class NotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);

    private final NotificationDispatcher dispatcher;
    private final NotificationService notifications;

    NotificationScheduler(NotificationDispatcher dispatcher, NotificationService notifications) {
        this.dispatcher = dispatcher;
        this.notifications = notifications;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "${app.notifications.poll:PT10M}")
    void dispatch() {
        try {
            int sent = dispatcher.dispatchDue(Instant.now());
            if (sent > 0) {
                log.info("Sent {} notification(s)", sent);
            }
        } catch (RuntimeException e) {
            log.warn("Notification dispatch failed: {}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(cron = "${app.notifications.purge-cron:0 45 3 * * *}")
    void purge() {
        try {
            notifications.purgeOld();
        } catch (RuntimeException e) {
            log.warn("Notification purge failed: {}", e.getClass().getSimpleName());
        }
    }
}
