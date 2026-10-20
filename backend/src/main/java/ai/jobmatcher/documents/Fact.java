package ai.jobmatcher.documents;

import java.util.List;

/**
 * One statement about the candidate that a document may rely on, copied from the reviewed CV. {@code text} is the CV's
 * own wording; the other fields only organise it. Nothing here is generated.
 */
public record Fact(String id, Type type, String text, String title, String company, String period, List<String> skills) {

    public enum Type { EXPERIENCE, SKILL, YEARS, EDUCATION, LANGUAGE }

    public static Fact simple(String id, Type type, String text, List<String> skills) {
        return new Fact(id, type, text, null, null, null, skills);
    }
}
