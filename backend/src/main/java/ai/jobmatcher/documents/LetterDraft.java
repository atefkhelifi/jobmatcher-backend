package ai.jobmatcher.documents;

import java.util.List;

/** A composed letter: fixed salutation and closing, plus body paragraphs that each cite the facts they rest on. */
public record LetterDraft(String salutation, List<Paragraph> paragraphs, String closing, String signature, String generator) {

    public record Paragraph(String text, List<String> factIds) {
    }

    public String plainText() {
        StringBuilder sb = new StringBuilder(salutation).append("\n\n");
        paragraphs.forEach(p -> sb.append(p.text()).append("\n\n"));
        sb.append(closing).append('\n').append(signature);
        return sb.toString().strip();
    }
}
