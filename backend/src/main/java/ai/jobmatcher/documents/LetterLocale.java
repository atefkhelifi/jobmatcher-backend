package ai.jobmatcher.documents;

import java.util.List;

/**
 * Wording for one language. Adding a language means implementing this interface and registering the bean; the
 * composer, the validator and the API need no change.
 */
public interface LetterLocale {

    String code();

    String salutation(LetterOptions.Formality formality);

    String opening(String title, String company, LetterOptions.Tone tone);

    /** "My background includes X, Y, which your posting asks for." */
    String skillsLine(List<String> skills);

    String experienceLine(Fact experience);

    String yearsLine(String years, List<String> skills);

    String educationLine(String text);

    String languageLine(String text);

    String motivationLine(String desiredTitle);

    String closingParagraph(LetterOptions.Tone tone);

    String closingFormula(LetterOptions.Formality formality);

    // ---- email ----

    String emailSubject(String title, String company);

    String emailGreeting(String contactName, LetterOptions.Formality formality);

    String emailIntro(String title, String company, String currentTitle, String years);

    String emailPointsIntro();

    /** One line naming the posting's skills that appear on the CV. */
    String emailSkillsLine(List<String> skills);

    String emailAttachments(boolean withLetter);

    String emailClosing(LetterOptions.Formality formality);

    default String join(List<String> items, String and) {
        if (items.size() <= 1) {
            return String.join("", items);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " " + and + " " + items.get(items.size() - 1);
    }

    /** "2019-01 – present" from "2019-01/present". */
    default String period(String raw, String presentWord) {
        if (raw == null) {
            return "";
        }
        String[] p = raw.split("/", 2);
        return p[0] + " – " + (p.length > 1 && p[1].equals("present") ? presentWord : p.length > 1 ? p[1] : "");
    }

    default String stripEnd(String s) {
        return s == null ? "" : s.strip().replaceAll("[.;,\s]+$", "");
    }
}
