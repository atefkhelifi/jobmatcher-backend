package ai.jobmatcher.documents;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import ai.jobmatcher.auth.CurrentUser;
import ai.jobmatcher.common.RateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.annotation.Value;
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
@RequestMapping("/api")
class DocumentController {

    public record LetterRequest(@NotNull UUID cvId, @Pattern(regexp = "[a-z]{2}", message = "must be a language code such as en or fr") String language,
            LetterOptions.Tone tone, LetterOptions.Length length, LetterOptions.Formality formality, Boolean useAi) {
        LetterOptions options() {
            return new LetterOptions(language, tone == null ? LetterOptions.Tone.NEUTRAL : tone,
                    length == null ? LetterOptions.Length.STANDARD : length, formality == null ? LetterOptions.Formality.FORMAL : formality,
                    useAi == null || useAi);
        }
    }

    public record EmailRequest(@NotNull UUID cvId, @Pattern(regexp = "[a-z]{2}") String language, LetterOptions.Formality formality,
            Boolean includeLetter, @Size(max = 100) String contactName) {
    }

    public record EditRequest(@NotNull @Size(max = 20_000) String text, @Size(max = 300) String subject) {
    }

    private final DocumentService service;
    private final RateLimiter limiter;
    private final int perMinute;

    DocumentController(DocumentService service, RateLimiter limiter, @Value("${app.rate-limit.documents-per-minute:10}") int perMinute) {
        this.service = service;
        this.limiter = limiter;
        this.perMinute = perMinute;
    }

    @PostMapping("/jobs/{jobId}/letters")
    @ResponseStatus(HttpStatus.CREATED)
    DocumentService.View createLetter(@PathVariable UUID jobId, @Valid @RequestBody LetterRequest body) {
        UUID user = CurrentUser.id();
        limiter.check("documents:" + user, perMinute, Duration.ofMinutes(1));
        return service.createLetter(user, jobId, body.cvId(), body.options());
    }

    @PostMapping("/jobs/{jobId}/emails")
    @ResponseStatus(HttpStatus.CREATED)
    DocumentService.View createEmail(@PathVariable UUID jobId, @Valid @RequestBody EmailRequest body) {
        UUID user = CurrentUser.id();
        limiter.check("documents:" + user, perMinute, Duration.ofMinutes(1));
        LetterOptions o = new LetterOptions(body.language(), LetterOptions.Tone.NEUTRAL, LetterOptions.Length.STANDARD,
                body.formality() == null ? LetterOptions.Formality.FORMAL : body.formality(), false);
        return service.createEmail(user, jobId, body.cvId(), o, body.includeLetter() == null || body.includeLetter(), body.contactName());
    }

    @GetMapping("/jobs/{jobId}/documents")
    List<DocumentService.Summary> versions(@PathVariable UUID jobId, @RequestParam GeneratedDocument.Type type) {
        return service.versions(CurrentUser.id(), jobId, type);
    }

    @GetMapping("/documents/languages")
    List<String> languages() {
        return service.supportedLanguages();
    }

    @GetMapping("/documents/{id}")
    DocumentService.View get(@PathVariable UUID id) {
        return service.get(CurrentUser.id(), id);
    }

    @PutMapping("/documents/{id}")
    DocumentService.View edit(@PathVariable UUID id, @Valid @RequestBody EditRequest body) {
        return service.edit(CurrentUser.id(), id, body.text(), body.subject());
    }

    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        service.delete(CurrentUser.id(), id);
    }

    @GetMapping("/documents/{id}/export")
    ResponseEntity<byte[]> export(@PathVariable UUID id, @RequestParam String format, @RequestParam(required = false) String to) {
        DocumentService.Export e = service.export(CurrentUser.id(), id, format, to);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(e.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + e.filename() + "\"")
                .header("X-Content-Type-Options", "nosniff").body(e.bytes());
    }
}
