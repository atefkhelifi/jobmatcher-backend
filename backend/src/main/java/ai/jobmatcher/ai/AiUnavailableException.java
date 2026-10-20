package ai.jobmatcher.ai;

/** The configured AI provider cannot answer. Callers fall back to deterministic results where they can. */
public class AiUnavailableException extends RuntimeException {
    public AiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public AiUnavailableException(String message) {
        super(message);
    }
}
