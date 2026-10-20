package ai.jobmatcher.learning;

import java.util.UUID;

import ai.jobmatcher.auth.CurrentUser;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/learning")
class LearningController {

    private final LearningPlanService service;

    LearningController(LearningPlanService service) {
        this.service = service;
    }

    @GetMapping("/plan")
    LearningPlanService.Plan plan(@RequestParam UUID cvId) {
        return service.plan(CurrentUser.id(), cvId);
    }
}
