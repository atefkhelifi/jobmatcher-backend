package ai.jobmatcher.ai;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Talks to a local Ollama server (https://ollama.com) through its /api/chat endpoint. */
public class OllamaTextGenerator implements TextGenerator {

    private final RestClient client;
    private final String model;

    public OllamaTextGenerator(String baseUrl, String model, Duration timeout) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(3));
        f.setReadTimeout(timeout);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
        this.model = model;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String generate(String system, String userContent, boolean jsonOutput) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("think", false);
        body.put("options", Map.of("temperature", 0.2));
        if (jsonOutput) {
            body.put("format", "json");
        }
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", "<untrusted_data>\n" + userContent + "\n</untrusted_data>")));
        try {
            Map<String, Object> res = client.post().uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(Map.class);
            Map<String, Object> msg = res == null ? null : (Map<String, Object>) res.get("message");
            if (msg == null || msg.get("content") == null) {
                throw new AiUnavailableException("Ollama returned no content");
            }
            return msg.get("content").toString();
        } catch (AiUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AiUnavailableException("Ollama request failed", e);
        }
    }

    /**
     * Puts untrusted text between data delimiters. Any delimiter inside the text is defused first, so a CV or job ad cannot
     * "close" the data block and continue as if it were instructions.
     */
    public static String wrap(String untrusted) {
        String safe = untrusted.replaceAll("(?i)<(/?)\\s*untrusted_data", "<\\\\$1untrusted_data");
        return "<untrusted_data>\n" + safe + "\n</untrusted_data>";
    }

    @Override
    public String name() {
        return "ollama:" + model;
    }

    @Override
    @SuppressWarnings("unchecked")
    public AiHealth health() {
        try {
            Map<String, Object> tags = client.get().uri("/api/tags").retrieve().body(Map.class);
            List<Map<String, Object>> models = tags == null ? List.of() : (List<Map<String, Object>>) tags.get("models");
            boolean present = models != null && models.stream().anyMatch(m -> {
                String n = String.valueOf(m.get("name"));
                return n.equals(model) || n.startsWith(model + ":");
            });
            return present
                    ? new AiHealth(true, "Ollama reachable, model " + model + " installed")
                    : new AiHealth(false, "Ollama reachable but model " + model + " is not installed. Run: ollama pull " + model);
        } catch (RuntimeException e) {
            return new AiHealth(false, "Ollama is not reachable. Start it (docker compose --profile ai up) or set AI_PROVIDER=mock");
        }
    }
}
