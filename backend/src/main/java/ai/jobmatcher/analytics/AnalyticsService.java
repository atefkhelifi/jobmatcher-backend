package ai.jobmatcher.analytics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ai.jobmatcher.applications.ApplicationEvent;
import ai.jobmatcher.applications.ApplicationEventRepository;
import ai.jobmatcher.applications.ApplicationRepository;
import ai.jobmatcher.applications.ApplicationStatus;
import ai.jobmatcher.applications.JobApplication;
import ai.jobmatcher.cv.CvService;
import ai.jobmatcher.jobs.JobOffer;
import ai.jobmatcher.jobs.JobOfferRepository;
import ai.jobmatcher.matching.JobSkillExtractor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Statistics about the user's own applications, and nothing else: no benchmarks against other people and no predictions.
 * Small samples are flagged so that a rate based on a handful of applications is not mistaken for a trend.
 */
@Service
public class AnalyticsService {

    /** Below this many applications a rate says very little. */
    static final int SMALL_SAMPLE = 10;
    /** A CV version is only compared when it has been used at least this many times. */
    static final int MIN_PER_CV = 5;
    static final int TOP = 10;

    public record Count(String key, int count) {
    }

    public record Stage(String status, int visits, Double averageDays) {
    }

    public record CvStat(UUID cvId, String label, int applied, int interviews, Double interviewRate) {
    }

    public record Overview(int tracked, int applied, int interviews, int offers, int rejectedAfterApplying, int waiting, int withdrawn,
            Double interviewRate, Double offerRate, boolean smallSample, int smallSampleBelow) {
    }

    public record View(Overview overview, List<Count> byStatus, List<Count> byCountry, List<Count> byRole, List<Count> skills,
            List<Stage> timePerStage, List<CvStat> byCv, UUID bestCvId, String bestCvNote) {
    }

    private static final Set<ApplicationStatus> APPLIED_OR_LATER = EnumSet.of(ApplicationStatus.APPLIED, ApplicationStatus.INTERVIEW,
            ApplicationStatus.ASSESSMENT, ApplicationStatus.OFFER);
    private static final Set<ApplicationStatus> INTERVIEW_STAGE = EnumSet.of(ApplicationStatus.INTERVIEW, ApplicationStatus.ASSESSMENT);

    private final ApplicationRepository apps;
    private final ApplicationEventRepository events;
    private final JobOfferRepository offers;
    private final JobSkillExtractor skillExtractor;
    private final CvService cvs;

    AnalyticsService(ApplicationRepository apps, ApplicationEventRepository events, JobOfferRepository offers, JobSkillExtractor skillExtractor,
            CvService cvs) {
        this.apps = apps;
        this.events = events;
        this.offers = offers;
        this.skillExtractor = skillExtractor;
        this.cvs = cvs;
    }

    @Transactional(readOnly = true)
    public View compute(UUID userId) {
        return compute(userId, Instant.now());
    }

    View compute(UUID userId, Instant now) {
        List<JobApplication> mine = apps.findByUserIdOrderByUpdatedAtDesc(userId);
        Map<UUID, List<ApplicationEvent>> timeline = new HashMap<>();
        for (ApplicationEvent e : events.findByUserIdOrderByOccurredAtAsc(userId)) {
            timeline.computeIfAbsent(e.getApplicationId(), k -> new ArrayList<>()).add(e);
        }

        int applied = 0;
        int interviews = 0;
        int offersCount = 0;
        int rejectedAfter = 0;
        int waiting = 0;
        int withdrawn = 0;
        Map<UUID, int[]> perCv = new LinkedHashMap<>(); // [applied, interviews]
        Map<ApplicationStatus, Integer> byStatus = new EnumMap<>(ApplicationStatus.class);
        Map<String, Integer> byCountry = new HashMap<>();
        Map<String, Integer> byRole = new HashMap<>();

        for (JobApplication a : mine) {
            byStatus.merge(a.getStatus(), 1, Integer::sum);
            if (a.getCountry() != null && !a.getCountry().isBlank()) {
                byCountry.merge(a.getCountry().toUpperCase(Locale.ROOT), 1, Integer::sum);
            }
            byRole.merge(role(a.getTitle()), 1, Integer::sum);
            if (a.getStatus() == ApplicationStatus.WITHDRAWN) {
                withdrawn++;
            }

            Set<ApplicationStatus> reached = reached(a, timeline.getOrDefault(a.getId(), List.of()));
            boolean wasApplied = a.getAppliedAt() != null || reached.stream().anyMatch(APPLIED_OR_LATER::contains);
            if (!wasApplied) {
                continue;
            }
            applied++;
            boolean interviewed = reached.stream().anyMatch(INTERVIEW_STAGE::contains);
            boolean offered = reached.contains(ApplicationStatus.OFFER);
            if (interviewed) {
                interviews++;
            }
            if (offered) {
                offersCount++;
            }
            if (a.getStatus() == ApplicationStatus.REJECTED) {
                rejectedAfter++;
            }
            if (a.getStatus() == ApplicationStatus.APPLIED) {
                waiting++;
            }
            if (a.getCvId() != null) {
                int[] c = perCv.computeIfAbsent(a.getCvId(), k -> new int[2]);
                c[0]++;
                if (interviewed) {
                    c[1]++;
                }
            }
        }

        Overview overview = new Overview(mine.size(), applied, interviews, offersCount, rejectedAfter, waiting, withdrawn,
                rate(interviews, applied), rate(offersCount, applied), applied < SMALL_SAMPLE, SMALL_SAMPLE);

        List<Count> status = new ArrayList<>();
        for (ApplicationStatus s : ApplicationStatus.values()) {
            if (byStatus.containsKey(s)) {
                status.add(new Count(s.name(), byStatus.get(s)));
            }
        }
        List<CvStat> cvStats = cvStats(userId, perCv);
        UUID best = null;
        String bestNote;
        List<CvStat> eligible = cvStats.stream().filter(c -> c.applied() >= MIN_PER_CV).toList();
        if (eligible.size() < 2) {
            bestNote = "NOT_ENOUGH_DATA";
        } else {
            CvStat top = eligible.stream().max(Comparator.comparingDouble(CvStat::interviewRate)).orElseThrow();
            long ties = eligible.stream().filter(c -> c.interviewRate().equals(top.interviewRate())).count();
            if (ties > 1) {
                bestNote = "NO_CLEAR_DIFFERENCE";
            } else {
                best = top.cvId();
                bestNote = "LEADS_BY_SMALL_SAMPLE";
            }
        }
        return new View(overview, status, top(byCountry), top(byRole), skills(userId, mine), timePerStage(mine, timeline, now), cvStats, best, bestNote);
    }

    /** Every status the application has been in, including the one it started in. */
    private static Set<ApplicationStatus> reached(JobApplication a, List<ApplicationEvent> history) {
        Set<ApplicationStatus> out = EnumSet.noneOf(ApplicationStatus.class);
        out.add(a.getStatus());
        for (ApplicationEvent e : history) {
            if (e.getToStatus() != null) {
                out.add(e.getToStatus());
            }
        }
        return out;
    }

    private List<CvStat> cvStats(UUID userId, Map<UUID, int[]> perCv) {
        Map<UUID, String> labels = new HashMap<>();
        cvs.list(userId).forEach(c -> labels.put(c.id(), c.label()));
        List<CvStat> out = new ArrayList<>();
        perCv.forEach((id, c) -> out.add(new CvStat(id, labels.getOrDefault(id, "?"), c[0], c[1], rate(c[1], c[0]))));
        out.sort(Comparator.comparingInt(CvStat::applied).reversed());
        return out;
    }

    /** The skills named in the jobs the user tracked, most frequent first. */
    private List<Count> skills(UUID userId, List<JobApplication> mine) {
        Set<UUID> jobIds = new HashSet<>();
        mine.stream().map(JobApplication::getJobId).filter(java.util.Objects::nonNull).forEach(jobIds::add);
        if (jobIds.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> counts = new HashMap<>();
        for (JobOffer job : offers.findAllById(jobIds)) {
            if (job.getOwnerUserId() != null && !job.getOwnerUserId().equals(userId)) {
                continue;
            }
            for (JobSkillExtractor.JobSkill s : skillExtractor.extract(job.getTitle(), job.getDescription())) {
                counts.merge(s.skill(), 1, Integer::sum);
            }
        }
        return top(counts);
    }

    private static List<Stage> timePerStage(List<JobApplication> mine, Map<UUID, List<ApplicationEvent>> timeline, Instant now) {
        Map<ApplicationStatus, long[]> acc = new EnumMap<>(ApplicationStatus.class); // [visits, total seconds]
        for (JobApplication a : mine) {
            List<ApplicationEvent> steps = timeline.getOrDefault(a.getId(), List.of()).stream().filter(e -> e.getToStatus() != null).toList();
            for (int i = 0; i < steps.size(); i++) {
                ApplicationStatus s = steps.get(i).getToStatus();
                if (s.isFinal()) {
                    continue;
                }
                Instant start = steps.get(i).getOccurredAt();
                Instant end = i + 1 < steps.size() ? steps.get(i + 1).getOccurredAt() : now;
                long[] c = acc.computeIfAbsent(s, k -> new long[2]);
                c[0]++;
                c[1] += Math.max(0, Duration.between(start, end).getSeconds());
            }
        }
        List<Stage> out = new ArrayList<>();
        for (ApplicationStatus s : ApplicationStatus.values()) {
            long[] c = acc.get(s);
            if (c != null) {
                out.add(new Stage(s.name(), (int) c[0], Math.round(c[1] / 86400.0 / c[0] * 10) / 10.0));
            }
        }
        return out;
    }

    /** "Senior Java Developer (m/f/d)" and "senior java developer" count as one role. */
    static String role(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("\\(.*?\\)|\\[.*?]", " ").replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? "?" : t;
    }

    private static List<Count> top(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP).map(e -> new Count(e.getKey(), e.getValue())).toList();
    }

    private static Double rate(int part, int whole) {
        return whole == 0 ? null : Math.round(part * 1000.0 / whole) / 10.0;
    }
}
