package ai.jobmatcher.comparison;

import java.time.Duration;
import java.util.UUID;

import ai.jobmatcher.auth.CurrentUser;
import ai.jobmatcher.common.RateLimiter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cv-comparison")
class CvComparisonController {

    private final CvComparisonService service;
    private final RateLimiter limiter;
    private final int perMinute;

    CvComparisonController(CvComparisonService service, RateLimiter limiter, @Value("${app.rate-limit.matching-per-minute:20}") int perMinute) {
        this.service = service;
        this.limiter = limiter;
        this.perMinute = perMinute;
    }

    @GetMapping
    CvComparisonService.Comparison compare(@RequestParam UUID a, @RequestParam UUID b, @RequestParam(required = false) UUID jobId) {
        UUID user = CurrentUser.id();
        limiter.check("compare:" + user, perMinute, Duration.ofMinutes(1));
        return service.compare(user, a, b, jobId);
    }
}
