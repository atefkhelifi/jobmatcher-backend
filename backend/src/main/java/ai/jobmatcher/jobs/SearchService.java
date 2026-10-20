package ai.jobmatcher.jobs;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import ai.jobmatcher.common.InvalidInputException;
import ai.jobmatcher.common.NotFoundException;
import ai.jobmatcher.notifications.AlertService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SearchService {

    static final int MAX_SEARCHES = 20;
    static final int MAX_AUTO = 5;

    public record Request(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 200) String keywords,
            @Size(max = 120) String location,
            boolean remoteOnly,
            @Size(max = 10) List<@Size(max = 40) String> sourceKeys,
            boolean autoRun,
            @Min(1) @Max(168) Integer intervalHours,
            @Min(0) @Max(100) Integer minScore) {
    }

    public record AdHoc(@Size(max = 200) String keywords, @Size(max = 120) String location, boolean remoteOnly,
            @Size(max = 10) List<@Size(max = 40) String> sourceKeys) {
    }

    public record View(UUID id, String name, String keywords, String location, boolean remoteOnly, List<String> sourceKeys,
            boolean autoRun, int intervalHours, int minScore, Instant lastRunAt, Integer lastNewCount) {
    }

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };

    private final SearchConfigurationRepository repo;
    private final DiscoveryService discovery;
    private final JsonMapper json;
    private final AlertService alerts;

    SearchService(SearchConfigurationRepository repo, DiscoveryService discovery, JsonMapper json, AlertService alerts) {
        this.alerts = alerts;
        this.repo = repo;
        this.discovery = discovery;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<View> list(UUID userId) {
        return repo.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::view).toList();
    }

    @Transactional
    public View create(UUID userId, Request r) {
        if (repo.countByUserId(userId) >= MAX_SEARCHES) {
            throw new InvalidInputException("You can keep at most " + MAX_SEARCHES + " saved searches.");
        }
        if (r.autoRun() && repo.findByUserIdOrderByCreatedAtDesc(userId).stream().filter(SearchConfiguration::isAutoRun).count() >= MAX_AUTO) {
            throw new InvalidInputException("At most " + MAX_AUTO + " searches can run automatically.");
        }
        SearchConfiguration c = new SearchConfiguration();
        c.setUserId(userId);
        c.setName(r.name().trim());
        c.setKeywords(blankToNull(r.keywords()));
        c.setLocation(blankToNull(r.location()));
        c.setRemoteOnly(r.remoteOnly());
        c.setSourceKeys(json.writeValueAsString(knownKeys(r.sourceKeys())));
        c.setAutoRun(r.autoRun());
        c.setIntervalHours(r.intervalHours() == null ? 24 : r.intervalHours());
        c.setMinScore(r.minScore() == null ? 60 : r.minScore());
        return view(repo.save(c));
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        repo.delete(repo.findByIdAndUserId(id, userId).orElseThrow(() -> new NotFoundException("Search not found.")));
    }

    /** Runs a saved search and records when and how many new offers it found. */
    public DiscoveryService.RunResult runSaved(UUID userId, UUID id) {
        SearchConfiguration c = repo.findByIdAndUserId(id, userId).orElseThrow(() -> new NotFoundException("Search not found."));
        return execute(c, false);
    }

    public DiscoveryService.RunResult runAdHoc(AdHoc a) {
        return discovery.run(SearchQuery.of(a.keywords(), a.location(), a.remoteOnly()),
                a.sourceKeys() == null ? Set.of() : new HashSet<>(a.sourceKeys()));
    }

    /** Runs every automatic search whose interval has elapsed. Returns how many were run. */
    public int runDue() {
        Instant now = Instant.now();
        int ran = 0;
        for (SearchConfiguration c : repo.findByAutoRunTrue()) {
            if (c.getLastRunAt() == null || c.getLastRunAt().isBefore(now.minus(Duration.ofHours(c.getIntervalHours())))) {
                execute(c, true);
                ran++;
            }
        }
        return ran;
    }

    private DiscoveryService.RunResult execute(SearchConfiguration c, boolean automatic) {
        Instant started = Instant.now();
        DiscoveryService.RunResult result = discovery.run(SearchQuery.of(c.getKeywords(), c.getLocation(), c.isRemoteOnly()),
                new HashSet<>(read(c.getSourceKeys())));
        c.setLastRunAt(Instant.now());
        c.setLastNewCount(result.created());
        repo.save(c);
        if (automatic && result.created() > 0) {
            alerts.afterAutomaticRun(c, started);
        }
        return result;
    }

    private List<String> knownKeys(List<String> requested) {
        if (requested == null) {
            return List.of();
        }
        Set<String> known = new HashSet<>();
        discovery.adapters().forEach(a -> known.add(a.key()));
        return requested.stream().filter(known::contains).distinct().toList();
    }

    private View view(SearchConfiguration c) {
        return new View(c.getId(), c.getName(), c.getKeywords(), c.getLocation(), c.isRemoteOnly(), read(c.getSourceKeys()),
                c.isAutoRun(), c.getIntervalHours(), c.getMinScore(), c.getLastRunAt(), c.getLastNewCount());
    }

    private List<String> read(String s) {
        return json.readValue(s, STRINGS);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
