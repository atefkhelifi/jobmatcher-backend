package ai.jobmatcher.company;

import java.util.UUID;

import ai.jobmatcher.auth.CurrentUser;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs")
class CompanyController {

    private final CompanyService service;

    CompanyController(CompanyService service) {
        this.service = service;
    }

    @GetMapping("/{id}/company")
    CompanyService.Profile company(@PathVariable UUID id) {
        return service.forJob(CurrentUser.id(), id);
    }
}
