package ai.jobmatcher.analytics;

import ai.jobmatcher.auth.CurrentUser;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analytics")
class AnalyticsController {

    private final AnalyticsService service;

    AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @GetMapping
    AnalyticsService.View get() {
        return service.compute(CurrentUser.id());
    }
}
