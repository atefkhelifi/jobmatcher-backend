package ai.jobmatcher.contacts;

import java.time.Duration;
import java.util.UUID;

import ai.jobmatcher.auth.CurrentUser;
import ai.jobmatcher.common.RateLimiter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs/{jobId}/contacts")
class ContactController {

    private final ContactDiscoveryService service;
    private final RateLimiter limiter;
    private final int perMinute;

    ContactController(ContactDiscoveryService service, RateLimiter limiter, @Value("${app.rate-limit.discovery-per-minute:6}") int perMinute) {
        this.service = service;
        this.limiter = limiter;
        this.perMinute = perMinute;
    }

    @GetMapping
    ContactDiscoveryService.ContactsView get(@PathVariable UUID jobId) {
        return service.view(CurrentUser.id(), jobId);
    }

    /** Reads the offer text and, for employer-hosted offers, a few of the employer's own pages. */
    @PostMapping("/discover")
    ContactDiscoveryService.ContactsView discover(@PathVariable UUID jobId) {
        UUID user = CurrentUser.id();
        limiter.check("contacts:" + user, perMinute, Duration.ofMinutes(1));
        return service.discover(user, jobId);
    }
}
