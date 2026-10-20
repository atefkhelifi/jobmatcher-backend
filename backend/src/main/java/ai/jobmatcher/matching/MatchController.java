package ai.jobmatcher.matching;

import java.time.Duration;
import java.util.UUID;

import ai.jobmatcher.auth.CurrentUser;
import ai.jobmatcher.common.RateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class MatchController {

    public record RunRequest(@NotNull UUID cvId) {
    }

    private final MatchService service;
    private final RateLimiter limiter;
    private final int perMinute;

    MatchController(MatchService service, RateLimiter limiter, @Value("${app.rate-limit.matching-per-minute:20}") int perMinute) {
        this.service = service;
        this.limiter = limiter;
        this.perMinute = perMinute;
    }

    /** Scores every visible, active offer against one CV. Only offers whose inputs changed are recomputed. */
    @PostMapping("/matches/run")
    MatchService.RunSummary run(@Valid @RequestBody RunRequest body) {
        UUID user = CurrentUser.id();
        limiter.check("matching:" + user, perMinute, Duration.ofMinutes(1));
        return service.runAll(user, body.cvId());
    }

    @GetMapping("/jobs/{id}/match")
    MatchService.View one(@PathVariable UUID id, @RequestParam UUID cvId) {
        UUID user = CurrentUser.id();
        limiter.check("matching:" + user, perMinute * 6, Duration.ofMinutes(1));
        return service.match(user, cvId, id);
    }
}
