package ai.jobmatcher.notifications;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import ai.jobmatcher.auth.CurrentUser;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
class NotificationController {

    private final NotificationService service;

    NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    List<NotificationService.View> list(@RequestParam(defaultValue = "50") int limit) {
        return service.list(CurrentUser.id(), limit);
    }

    @GetMapping("/unread-count")
    Map<String, Long> unread() {
        return Map.of("unread", service.unread(CurrentUser.id()));
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void read(@PathVariable UUID id) {
        service.markRead(CurrentUser.id(), id);
    }

    @PostMapping("/read-all")
    Map<String, Integer> readAll() {
        return Map.of("marked", service.markAllRead(CurrentUser.id()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        service.delete(CurrentUser.id(), id);
    }
}
