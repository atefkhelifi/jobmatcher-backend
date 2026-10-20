package ai.jobmatcher.cv;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import ai.jobmatcher.auth.CurrentUser;
import ai.jobmatcher.common.NotFoundException;
import ai.jobmatcher.common.RateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/cvs")
class CvController {

    public record TextCv(@NotBlank @Size(max = 160) String label, @NotBlank @Size(max = 200_000) String text) {
    }

    public record ReviewedCv(@Size(max = 160) String label, @Valid ParsedCv parsed) {
    }

    private final CvService service;
    private final RateLimiter limiter;
    private final int uploadsPerMinute;
    private final int analysesPerMinute;

    CvController(CvService service, RateLimiter limiter, @Value("${app.rate-limit.upload-per-minute}") int uploadsPerMinute,
            @Value("${app.rate-limit.analysis-per-minute}") int analysesPerMinute) {
        this.service = service;
        this.limiter = limiter;
        this.uploadsPerMinute = uploadsPerMinute;
        this.analysesPerMinute = analysesPerMinute;
    }

    @GetMapping
    List<CvService.Summary> list() {
        return service.list(CurrentUser.id());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    CvService.Detail upload(@RequestParam("file") MultipartFile file, @RequestParam(value = "label", required = false) String label) {
        UUID user = CurrentUser.id();
        limiter.check("upload:" + user, uploadsPerMinute, Duration.ofMinutes(1));
        return service.upload(user, file, label);
    }

    @PostMapping(path = "/text", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    CvService.Detail fromText(@Valid @RequestBody TextCv body) {
        UUID user = CurrentUser.id();
        limiter.check("upload:" + user, uploadsPerMinute, Duration.ofMinutes(1));
        return service.createFromText(user, body.label(), body.text());
    }

    @GetMapping("/{id}")
    CvService.Detail get(@PathVariable UUID id) {
        return service.get(CurrentUser.id(), id);
    }

    @PutMapping("/{id}/profile")
    CvService.Detail saveReviewed(@PathVariable UUID id, @Valid @RequestBody ReviewedCv body) {
        return service.saveReviewed(CurrentUser.id(), id, body.parsed(), body.label());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        service.delete(CurrentUser.id(), id);
    }

    @PostMapping("/{id}/analysis")
    @ResponseStatus(HttpStatus.CREATED)
    CvService.AnalysisView analyze(@PathVariable UUID id, @RequestParam(defaultValue = "true") boolean ai) {
        UUID user = CurrentUser.id();
        limiter.check("analysis:" + user, analysesPerMinute, Duration.ofMinutes(1));
        return service.analyze(user, id, ai);
    }

    @GetMapping("/{id}/analysis")
    CvService.AnalysisView latestAnalysis(@PathVariable UUID id) {
        return service.latestAnalysis(CurrentUser.id(), id).orElseThrow(() -> new NotFoundException("No analysis yet for this CV."));
    }

    @GetMapping(value = "/{id}/export.md", produces = "text/markdown;charset=UTF-8")
    ResponseEntity<String> exportMarkdown(@PathVariable UUID id) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"cv.md\"")
                .body(service.exportMarkdown(CurrentUser.id(), id));
    }
}
