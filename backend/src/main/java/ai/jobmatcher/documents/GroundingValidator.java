package ai.jobmatcher.documents;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that a text only uses numbers and proper names that exist in the candidate's CV or the offer. It cannot prove a
 * sentence is true, but it catches the typical inventions: a made-up percentage, employer, product or year.
 */
public final class GroundingValidator {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");
    private static final Pattern NAME = Pattern.compile("^\\p{Lu}[\\p{L}'’\\-]{2,}$");
    private static final Set<String> COMMON = Set.of("dear", "sir", "madam", "hello", "kind", "regards", "yours", "faithfully", "sincerely",
            "madame", "monsieur", "bonjour", "cordialement", "english", "french", "german", "spanish", "italian", "dutch", "portuguese",
            "arabic", "anglais", "français", "francais", "allemand", "espagnol", "italien", "january", "february", "march", "april",
            "may", "june", "july", "august", "september", "october", "november", "december", "janvier", "février", "mars", "avril",
            "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre", "monday", "tuesday", "wednesday",
            "thursday", "friday", "lundi", "mardi", "mercredi", "jeudi", "vendredi", "thank", "merci", "your", "votre", "the", "this",
            "that", "these", "those", "one", "his", "her", "its", "our", "notre", "mon", "ma", "mes", "ce", "cette", "ces", "dans",
            "avec", "pour", "chez", "and", "but", "with", "for", "from", "at", "in", "on", "as", "during", "among", "while", "when",
            "after", "before", "since", "also", "however", "moreover", "additionally", "furthermore", "ainsi", "enfin", "cependant", "hiring", "team", "equipe", "équipe");

    private GroundingValidator() {
    }

    /**
     * @param text the text to check
     * @param corpusLower lower-cased CV and offer text the text may draw on
     * @return the numbers and names that appear in {@code text} but nowhere in the corpus; empty when the text is grounded
     */
    public static List<String> unsupported(String text, String corpusLower) {
        Set<String> issues = new LinkedHashSet<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            String n = m.group();
            if (!corpusLower.contains(n) && !corpusLower.contains(n.replace(',', '.')) && !corpusLower.contains(n.replace('.', ','))) {
                issues.add(n);
            }
        }
        for (String sentence : text.split("\\R+|(?<=[.!?:;])\\s+")) {
            String[] tokens = sentence.strip().split("\\s+");
            for (int i = 1; i < tokens.length; i++) { // the first word of a sentence is capitalised anyway
                String t = tokens[i].replaceAll("^[\"'«(\\[]+|[\"'»)\\],.;:!?]+$", "");
                if (NAME.matcher(t).matches() && !COMMON.contains(t.toLowerCase(Locale.ROOT))
                        && !containsWord(corpusLower, t.toLowerCase(Locale.ROOT))) {
                    issues.add(t);
                }
            }
        }
        return List.copyOf(issues);
    }

    private static boolean containsWord(String corpusLower, String wordLower) {
        int from = 0;
        while (true) {
            int at = corpusLower.indexOf(wordLower, from);
            if (at < 0) {
                return false;
            }
            boolean startOk = at == 0 || !Character.isLetterOrDigit(corpusLower.charAt(at - 1));
            int end = at + wordLower.length();
            boolean endOk = end >= corpusLower.length() || !Character.isLetterOrDigit(corpusLower.charAt(end));
            if (startOk && endOk) {
                return true;
            }
            from = at + 1;
        }
    }
}
