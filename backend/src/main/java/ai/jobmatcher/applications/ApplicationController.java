package ai.jobmatcher.applications;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ai.jobmatcher.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/applications")
class ApplicationController {

    public record CreateRequest(UUID jobId, @Size(max = 300) String title, @Size(max = 300) String company, @Size(max = 300) String location,
            @Size(max = 2000) String jobUrl, ApplicationStatus status, UUID cvId, Boolean confirmDuplicate) {
    }

    public record UpdateRequest(@NotNull @Size(max = 300) String title, @Size(max = 300) String company, @Size(max = 300) String location,
            @Size(max = 2000) String jobUrl, @Size(max = 10_000) String notes, Instant appliedAt, Instant deadlineAt, UUID cvId) {
    }

    public record StatusRequest(@NotNull ApplicationStatus status, @Size(max = 2000) String note, Integer followUpDays) {
    }

    public record NoteRequest(@NotNull @Size(max = 5000) String text) {
    }

    public record ContactRequest(@NotNull @Size(max = 200) String name, @Size(max = 200) String role, @Size(max = 254) String email,
            @Size(max = 60) String phone, @Size(max = 2000) String note) {
    }

    public record InterviewRequest(ApplicationInterview.Kind kind, @NotNull Instant scheduledAt, Integer durationMinutes,
            @Size(max = 500) String location, @Size(max = 2000) String notes) {
    }

    public record ReminderRequest(@NotNull Instant dueAt, @NotNull @Size(max = 500) String text) {
    }

    public record DoneRequest(boolean done) {
    }

    public record AttachmentRequest(UUID documentId, @Size(max = 200) String label, @Size(max = 2000) String url) {
    }

    private final ApplicationService service;

    ApplicationController(ApplicationService service) {
        this.service = service;
    }

    @GetMapping
    List<ApplicationService.Summary> list(@RequestParam(required = false) ApplicationStatus status) {
        return service.list(CurrentUser.id(), status);
    }

    @GetMapping("/dashboard")
    ApplicationService.Dashboard dashboard() {
        return service.dashboard(CurrentUser.id());
    }

    /** Asks whether tracking this role would duplicate an existing application, before the user creates it. */
    @GetMapping("/check")
    List<ApplicationService.Duplicate> check(@RequestParam(required = false) UUID jobId, @RequestParam(required = false) @Size(max = 300) String title,
            @RequestParam(required = false) @Size(max = 300) String company, @RequestParam(required = false) @Size(max = 300) String location) {
        return service.duplicatesFor(CurrentUser.id(), jobId, title, company, location);
    }

    @GetMapping(value = "/calendar.ics", produces = "text/calendar;charset=UTF-8")
    ResponseEntity<String> calendar(@RequestParam(required = false) UUID applicationId) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/calendar;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"jobmatcher.ics\"")
                .body(service.calendar(CurrentUser.id(), applicationId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApplicationService.Detail create(@Valid @RequestBody CreateRequest r) {
        return service.create(CurrentUser.id(), new ApplicationService.Create(r.jobId(), r.title(), r.company(), r.location(), r.jobUrl(), r.status(),
                r.cvId(), Boolean.TRUE.equals(r.confirmDuplicate())));
    }

    @GetMapping("/{id}")
    ApplicationService.Detail get(@PathVariable UUID id) {
        return service.get(CurrentUser.id(), id);
    }

    @PutMapping("/{id}")
    ApplicationService.Detail update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest r) {
        return service.update(CurrentUser.id(), id,
                new ApplicationService.Update(r.title(), r.company(), r.location(), r.jobUrl(), r.notes(), r.appliedAt(), r.deadlineAt(), r.cvId()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        service.delete(CurrentUser.id(), id);
    }

    @PostMapping("/{id}/status")
    ApplicationService.Detail move(@PathVariable UUID id, @Valid @RequestBody StatusRequest r) {
        return service.moveTo(CurrentUser.id(), id, r.status(), r.note(), r.followUpDays());
    }

    @PostMapping("/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    ApplicationService.Detail note(@PathVariable UUID id, @Valid @RequestBody NoteRequest r) {
        return service.addNote(CurrentUser.id(), id, r.text());
    }

    @PostMapping("/{id}/contacts")
    @ResponseStatus(HttpStatus.CREATED)
    ApplicationService.Detail addContact(@PathVariable UUID id, @Valid @RequestBody ContactRequest r) {
        return service.addContact(CurrentUser.id(), id, r.name(), r.role(), r.email(), r.phone(), r.note());
    }

    @DeleteMapping("/{id}/contacts/{contactId}")
    ApplicationService.Detail removeContact(@PathVariable UUID id, @PathVariable UUID contactId) {
        return service.removeContact(CurrentUser.id(), id, contactId);
    }

    @PostMapping("/{id}/interviews")
    @ResponseStatus(HttpStatus.CREATED)
    ApplicationService.Detail addInterview(@PathVariable UUID id, @Valid @RequestBody InterviewRequest r) {
        return service.addInterview(CurrentUser.id(), id, r.kind(), r.scheduledAt(), r.durationMinutes(), r.location(), r.notes());
    }

    @DeleteMapping("/{id}/interviews/{interviewId}")
    ApplicationService.Detail removeInterview(@PathVariable UUID id, @PathVariable UUID interviewId) {
        return service.removeInterview(CurrentUser.id(), id, interviewId);
    }

    @PostMapping("/{id}/reminders")
    @ResponseStatus(HttpStatus.CREATED)
    ApplicationService.Detail addReminder(@PathVariable UUID id, @Valid @RequestBody ReminderRequest r) {
        return service.addReminder(CurrentUser.id(), id, r.dueAt(), r.text());
    }

    @PostMapping("/{id}/reminders/{reminderId}/done")
    ApplicationService.Detail done(@PathVariable UUID id, @PathVariable UUID reminderId, @RequestBody DoneRequest r) {
        return service.completeReminder(CurrentUser.id(), id, reminderId, r.done());
    }

    @DeleteMapping("/{id}/reminders/{reminderId}")
    ApplicationService.Detail removeReminder(@PathVariable UUID id, @PathVariable UUID reminderId) {
        return service.removeReminder(CurrentUser.id(), id, reminderId);
    }

    @PostMapping("/{id}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    ApplicationService.Detail addAttachment(@PathVariable UUID id, @Valid @RequestBody AttachmentRequest r) {
        return service.addAttachment(CurrentUser.id(), id, r.documentId(), r.label(), r.url());
    }

    @DeleteMapping("/{id}/attachments/{attachmentId}")
    ApplicationService.Detail removeAttachment(@PathVariable UUID id, @PathVariable UUID attachmentId) {
        return service.removeAttachment(CurrentUser.id(), id, attachmentId);
    }
}
