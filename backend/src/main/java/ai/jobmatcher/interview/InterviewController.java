package ai.jobmatcher.interview;

import java.time.Duration;
import java.util.UUID;

import ai.jobmatcher.auth.CurrentUser;
import ai.jobmatcher.common.RateLimiter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs")
class InterviewController {

    private final InterviewPrepService service;
    private final RateLimiter limiter;
    private final int perMinute;

    InterviewController(InterviewPrepService service, RateLimiter limiter, @Value("${app.rate-limit.matching-per-minute:20}") int perMinute) {
        this.service = service;
        this.limiter = limiter;
        this.perMinute = perMinute;
    }

    @GetMapping("/{id}/interview-prep")
    InterviewPrepService.Prep prep(@PathVariable UUID id, @RequestParam UUID cvId, @RequestParam(required = false) String lang) {
        UUID user = CurrentUser.id();
        limiter.check("matching:" + user, perMinute * 6, Duration.ofMinutes(1));
        return service.prepare(user, id, cvId, lang);
    }
}
