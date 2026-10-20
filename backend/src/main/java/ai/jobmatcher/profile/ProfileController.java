package ai.jobmatcher.profile;

import ai.jobmatcher.auth.CurrentUser;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profile")
class ProfileController {

    private final ProfileService service;

    ProfileController(ProfileService service) {
        this.service = service;
    }

    @GetMapping
    ProfileService.Preferences get() {
        return service.get(CurrentUser.id());
    }

    @PutMapping
    ProfileService.Preferences update(@Valid @RequestBody ProfileService.Preferences body) {
        return service.update(CurrentUser.id(), body);
    }
}
