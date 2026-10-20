package ai.jobmatcher.documents;

/** Choices the user makes for a letter. */
public record LetterOptions(String language, Tone tone, Length length, Formality formality, boolean useAi) {

    public enum Tone { NEUTRAL, WARM, DIRECT }

    public enum Length { SHORT, STANDARD, DETAILED }

    public enum Formality { FORMAL, STANDARD }

    public static LetterOptions defaults(String language) {
        return new LetterOptions(language, Tone.NEUTRAL, Length.STANDARD, Formality.FORMAL, true);
    }
}
