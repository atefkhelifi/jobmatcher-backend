package ai.jobmatcher.ai;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, non-sensitive status so the UI can say plainly which optional components are working. */
@RestController
@RequestMapping("/api/status")
class StatusController {

    private final TextGenerator generator;

    StatusController(TextGenerator generator) {
        this.generator = generator;
    }

    @GetMapping
    Map<String, Object> status() {
        TextGenerator.AiHealth h = generator.health();
        return Map.of(
                "api", "ok",
                "ai", Map.of("provider", generator.name(), "available", h.available(), "detail", h.detail()));
    }
}
