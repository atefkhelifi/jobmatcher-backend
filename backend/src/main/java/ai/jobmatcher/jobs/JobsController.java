package ai.jobmatcher.jobs;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ai.jobmatcher.auth.CurrentUser;
import ai.jobmatcher.common.RateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class JobsController {

    public record ImportRequest(@NotBlank @Size(max = 2000) String url) {
    }

    public record SourceView(String key, String name, boolean enabled, String disabledReason, String termsUrl, String state,
            java.time.Instant lastSuccessAt, String lastError, int consecutiveFailures, Integer lastItemCount, long activeOffers) {
    }

    private final JobQueryService jobs;
    private final SearchService searches;
    private final DiscoveryService discovery;
    private final SourceHealthRepository health;
    private final JobOfferRepository offers;
    private final RateLimiter limiter;
    private final int runsPerMinute;

    JobsController(JobQueryService jobs, SearchService searches, DiscoveryService discovery, SourceHealthRepository health,
            JobOfferRepository offers, RateLimiter limiter, @Value("${app.rate-limit.discovery-per-minute:6}") int runsPerMinute) {
        this.jobs = jobs;
        this.searches = searches;
        this.discovery = discovery;
        this.health = health;
        this.offers = offers;
        this.limiter = limiter;
        this.runsPerMinute = runsPerMinute;
    }

    @GetMapping("/jobs")
    JobQueryService.PageView list(@RequestParam(required = false) String q, @RequestParam(required = false) String location,
            @RequestParam(required = false) RemoteMode remote, @RequestParam(required = false) String source,
            @RequestParam(required = false) Integer postedWithinDays, @RequestParam(required = false) Integer minSalary,
            @RequestParam(defaultValue = "true") boolean includeUnknownSalary, @RequestParam(defaultValue = "false") boolean includeExpired,
            @RequestParam(defaultValue = "newest") String sort, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) UUID cvId,
            @RequestParam(required = false) Integer minScore) {
        return jobs.search(CurrentUser.id(), new JobQueryService.Filter(q, location, remote, source, postedWithinDays, minSalary,
                includeUnknownSalary, includeExpired, sort, page, size, cvId, minScore));
    }

    @GetMapping("/jobs/{id}")
    JobQueryService.Detail get(@PathVariable UUID id) {
        return jobs.get(CurrentUser.id(), id);
    }

    @PostMapping("/jobs/import")
    @ResponseStatus(HttpStatus.CREATED)
    JobQueryService.Detail importUrl(@Valid @RequestBody ImportRequest body) {
        UUID user = CurrentUser.id();
        limiter.check("import:" + user, runsPerMinute, Duration.ofMinutes(1));
        return jobs.importUrl(user, body.url());
    }

    @GetMapping("/sources")
    List<SourceView> sources() {
        Map<String, Long> counts = new java.util.HashMap<>();
        offers.countActiveBySource().forEach(c -> counts.put(c.getKey(), c.getTotal()));
        return discovery.adapters().stream().map(a -> {
            SourceHealth h = health.findById(a.key()).orElse(new SourceHealth(a.key()));
            return new SourceView(a.key(), a.displayName(), a.enabled(), a.enabled() ? null : a.disabledReason(), a.termsUrl(),
                    a.enabled() ? h.state() : "disabled", h.getLastSuccessAt(), h.getLastError(), h.getConsecutiveFailures(),
                    h.getLastItemCount(), counts.getOrDefault(a.key(), 0L));
        }).toList();
    }

    @GetMapping("/searches")
    List<SearchService.View> savedSearches() {
        return searches.list(CurrentUser.id());
    }

    @PostMapping("/searches")
    @ResponseStatus(HttpStatus.CREATED)
    SearchService.View createSearch(@Valid @RequestBody SearchService.Request body) {
        return searches.create(CurrentUser.id(), body);
    }

    @DeleteMapping("/searches/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteSearch(@PathVariable UUID id) {
        searches.delete(CurrentUser.id(), id);
    }

    @PostMapping("/searches/{id}/run")
    DiscoveryService.RunResult runSaved(@PathVariable UUID id) {
        UUID user = CurrentUser.id();
        limiter.check("discovery:" + user, runsPerMinute, Duration.ofMinutes(1));
        return searches.runSaved(user, id);
    }

    @PostMapping("/searches/run")
    DiscoveryService.RunResult runAdHoc(@Valid @RequestBody SearchService.AdHoc body) {
        limiter.check("discovery:" + CurrentUser.id(), runsPerMinute, Duration.ofMinutes(1));
        return searches.runAdHoc(body);
    }
}
