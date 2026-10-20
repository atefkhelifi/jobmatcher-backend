package ai.jobmatcher.ai;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AiConfig {

    @Bean
    TextGenerator textGenerator(
            @Value("${app.ai.provider}") String provider,
            @Value("${app.ai.ollama.base-url}") String baseUrl,
            @Value("${app.ai.ollama.model}") String model,
            @Value("${app.ai.ollama.timeout}") Duration timeout) {
        return switch (provider.toLowerCase()) {
            case "mock" -> new MockTextGenerator();
            case "ollama" -> new OllamaTextGenerator(baseUrl, model, timeout);
            default -> throw new IllegalStateException("Unknown app.ai.provider '" + provider + "' (use mock or ollama)");
        };
    }
}
