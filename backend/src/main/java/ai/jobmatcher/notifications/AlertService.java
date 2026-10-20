package ai.jobmatcher.notifications;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import ai.jobmatcher.cv.CvService;
import ai.jobmatcher.jobs.JobOffer;
import ai.jobmatcher.jobs.JobOfferRepository;
import ai.jobmatcher.jobs.RemoteMode;
import ai.jobmatcher.jobs.SearchConfiguration;
import ai.jobmatcher.matching.MatchService;
import ai.jobmatcher.profile.ProfileService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Job alerts. After an automatic search has run, the offers it discovered for the first time are checked against the search
 * filters and, when the user has a CV, scored; the user is told once per run about the ones at or above the search's
 * threshold. Without a CV the user is told about new offers that fit the search. Opt-out through the notification preferences.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    static final int MAX_CANDIDATES = 300;
    static final int TOP = 3;

    private final JobOfferRepository offers;
    private final CvService cvs;
    private final MatchService matching;
    private final ProfileService profiles;
    private final NotificationService notifications;

    AlertService(JobOfferRepository offers, CvService cvs, MatchService matching, ProfileService profiles, NotificationService notifications) {
        this.offers = offers;
        this.cvs = cvs;
        this.matching = matching;
        this.profiles = profiles;
        this.notifications = notifications;
    }

    /** @return the number of offers mentioned in the notification, 0 when none was sent */
    public int afterAutomaticRun(SearchConfiguration search, Instant runStartedAt) {
        UUID user = search.getUserId();
        if (!profiles.get(user).notifyNewMatches()) {
            return 0;
        }
        List<JobOffer> fresh = offers.findNewCatalogOffersSince(runStartedAt, PageRequest.of(0, MAX_CANDIDATES)).stream()
                .filter(o -> fits(search, o)).toList();
        if (fresh.isEmpty()) {
            return 0;
        }
        List<CvService.Summary> mine = cvs.list(user);
        if (mine.isEmpty()) {
            notifications.notify(user, Notification.Type.NEW_MATCHES, "alert.newOffers",
                    Map.of("search", search.getName(), "count", fresh.size(), "titles", titles(fresh.stream().limit(TOP).toList())), "/app/jobs");
            return fresh.size();
        }
        UUID cv = mine.get(0).id();
        record Scored(JobOffer job, int score) {
        }
        List<Scored> good = new ArrayList<>();
        for (JobOffer o : fresh) {
            try {
                Integer score = matching.match(user, cv, o.getId()).report().score();
                if (score != null && score >= search.getMinScore()) {
                    good.add(new Scored(o, score));
                }
            } catch (RuntimeException e) {
                log.debug("Could not score an offer for an alert: {}", e.getClass().getSimpleName());
            }
        }
        if (good.isEmpty()) {
            return 0;
        }
        good.sort(Comparator.comparingInt(Scored::score).reversed());
        List<Map<String, Object>> top = good.stream().limit(TOP)
                .map(s -> Map.<String, Object>of("title", s.job().getTitle(), "company", s.job().getCompany() == null ? "" : s.job().getCompany(),
                        "score", s.score()))
                .toList();
        notifications.notify(user, Notification.Type.NEW_MATCHES, "alert.newMatches",
                Map.of("search", search.getName(), "count", good.size(), "minScore", search.getMinScore(), "top", top), "/app/jobs");
        return good.size();
    }

    /** The saved search's own filters, applied again to what the sources returned. */
    static boolean fits(SearchConfiguration s, JobOffer o) {
        if (s.isRemoteOnly() && o.getRemoteMode() != RemoteMode.REMOTE) {
            return false;
        }
        if (s.getLocation() != null && !s.getLocation().isBlank()
                && (o.getLocationText() == null || !o.getLocationText().toLowerCase(Locale.ROOT).contains(s.getLocation().toLowerCase(Locale.ROOT)))) {
            return false;
        }
        if (s.getKeywords() != null && !s.getKeywords().isBlank()) {
            String hay = (o.getTitle() + " " + o.getCompany() + " " + o.getDescription()).toLowerCase(Locale.ROOT);
            for (String k : s.getKeywords().toLowerCase(Locale.ROOT).split("\\s+")) {
                if (!k.isBlank() && !hay.contains(k)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static List<Map<String, Object>> titles(List<JobOffer> jobs) {
        return jobs.stream().map(j -> Map.<String, Object>of("title", j.getTitle(), "company", j.getCompany() == null ? "" : j.getCompany())).toList();
    }
}
