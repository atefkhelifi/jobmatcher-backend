package ai.jobmatcher.ai;

/** Deterministic provider for tests and for running without any model. */
public class MockTextGenerator implements TextGenerator {

    @Override
    public String generate(String system, String userContent, boolean jsonOutput) {
        return jsonOutput ? "{}" : "[mock] " + Integer.toHexString((system + "|" + userContent).hashCode());
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public AiHealth health() {
        return new AiHealth(true, "Deterministic mock provider (no model is called)");
    }
}
