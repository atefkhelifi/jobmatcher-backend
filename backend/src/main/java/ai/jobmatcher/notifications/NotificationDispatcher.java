package ai.jobmatcher.notifications;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import ai.jobmatcher.applications.ApplicationInterview;
import ai.jobmatcher.applications.ApplicationInterviewRepository;
import ai.jobmatcher.applications.ApplicationReminder;
import ai.jobmatcher.applications.ApplicationReminderRepository;
import ai.jobmatcher.applications.ApplicationRepository;
import ai.jobmatcher.applications.JobApplication;
import ai.jobmatcher.profile.ProfileService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns due follow-up reminders and interviews starting within a day into in-app notifications, once each. The notification
 * names the role, never the private text of a reminder. Users can switch these off in their preferences.
 */
@Service
public class NotificationDispatcher {

    static final Duration INTERVIEW_LEAD = Duration.ofHours(24);

    private final ApplicationRepository apps;
    private final ApplicationReminderRepository reminders;
    private final ApplicationInterviewRepository interviews;
    private final ProfileService profiles;
    private final NotificationService notifications;

    NotificationDispatcher(ApplicationRepository apps, ApplicationReminderRepository reminders, ApplicationInterviewRepository interviews,
            ProfileService profiles, NotificationService notifications) {
        this.apps = apps;
        this.reminders = reminders;
        this.interviews = interviews;
        this.profiles = profiles;
        this.notifications = notifications;
    }

    /** @return how many notifications were created */
    @Transactional
    public int dispatchDue(Instant now) {
        Map<UUID, Boolean> wants = new HashMap<>();
        int sent = 0;
        for (ApplicationReminder r : reminders.findByDoneAtIsNullAndNotifiedAtIsNullAndDueAtBefore(now)) {
            r.setNotifiedAt(now); // marked even when the user opted out, so it is not looked at again
            reminders.save(r);
            JobApplication a = apps.findById(r.getApplicationId()).orElse(null);
            if (a != null && wantsReminders(wants, r.getUserId())) {
                notifications.notify(r.getUserId(), Notification.Type.REMINDER_DUE, "reminder.due", roleParams(a), "/app/applications/" + a.getId());
                sent++;
            }
        }
        for (ApplicationInterview i : interviews.findByNotifiedAtIsNullAndScheduledAtBetween(now, now.plus(INTERVIEW_LEAD))) {
            i.setNotifiedAt(now);
            interviews.save(i);
            JobApplication a = apps.findById(i.getApplicationId()).orElse(null);
            if (a != null && !a.getStatus().isFinal() && wantsReminders(wants, a.getUserId())) {
                Map<String, Object> p = new HashMap<>(roleParams(a));
                p.put("scheduledAt", i.getScheduledAt().toString());
                p.put("kind", i.getKind().name());
                notifications.notify(a.getUserId(), Notification.Type.INTERVIEW_SOON, "interview.soon", p, "/app/applications/" + a.getId());
                sent++;
            }
        }
        return sent;
    }

    private boolean wantsReminders(Map<UUID, Boolean> cache, UUID userId) {
        return cache.computeIfAbsent(userId, u -> profiles.get(u).notifyReminders());
    }

    private static Map<String, Object> roleParams(JobApplication a) {
        return Map.of("applicationId", a.getId().toString(), "title", a.getTitle(), "company", a.getCompany() == null ? "" : a.getCompany());
    }
}
