package ai.jobmatcher.ai;

/**
 * Port for text generation. Business code depends on this interface only, so a local model,
 * an OpenAI-compatible server or a deterministic mock can be swapped via configuration.
 *
 * <p>Implementations must treat {@code userContent} as untrusted data: it is never concatenated into
 * the system instructions and the model is given no tools.
 */
public interface TextGenerator {

    /** @param system trusted instructions written by this application
     *  @param userContent untrusted text (CV, job ad) passed as delimited data
     *  @param jsonOutput request strictly JSON output */
    String generate(String system, String userContent, boolean jsonOutput);

    /** Identifier shown in the status page, e.g. {@code ollama:qwen3:8b}. */
    String name();

    /** Cheap reachability probe used by the status endpoint. Must not throw. */
    AiHealth health();

    record AiHealth(boolean available, String detail) {
    }
}
