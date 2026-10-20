package ai.jobmatcher.matching;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lexical similarity (cosine over term frequencies, French and English stop words removed). It measures shared
 * vocabulary, not meaning, and is labelled that way in the report. A sentence-embedding model can replace it later
 * behind the same method.
 */
final class TextSimilarity {

    private static final Set<String> STOP = new HashSet<>(java.util.List.of(
            "the", "and", "for", "with", "you", "our", "are", "will", "have", "has", "that", "this", "from", "your", "all", "any", "can",
            "not", "but", "who", "what", "their", "they", "about", "into", "such", "per", "more", "also", "work", "team", "role", "job",
            "company", "experience", "years", "year", "working", "join", "looking", "ability", "skills", "strong", "good", "new",
            "les", "des", "une", "pour", "avec", "dans", "sur", "par", "vous", "nous", "est", "sont", "aux", "que", "qui", "plus", "ses",
            "votre", "notre", "entreprise", "poste", "équipe", "equipe", "expérience", "experience", "ans", "avoir", "être", "etre",
            "der", "die", "das", "und", "mit", "für", "von", "zu", "den", "ein", "eine"));

    private TextSimilarity() {
    }

    /** Cosine similarity in [0, 1]. */
    static double cosine(String a, String b) {
        Map<String, Integer> fa = freq(a);
        Map<String, Integer> fb = freq(b);
        if (fa.isEmpty() || fb.isEmpty()) {
            return 0;
        }
        double dot = 0;
        for (Map.Entry<String, Integer> e : fa.entrySet()) {
            Integer o = fb.get(e.getKey());
            if (o != null) {
                dot += (double) e.getValue() * o;
            }
        }
        return dot / (norm(fa) * norm(fb));
    }

    static Set<String> tokens(String s) {
        return freq(s).keySet();
    }

    private static Map<String, Integer> freq(String s) {
        Map<String, Integer> m = new HashMap<>();
        if (s == null) {
            return m;
        }
        for (String t : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}+#.]+")) {
            String w = t.replaceAll("^[.]+|[.]+$", "");
            if (w.length() >= 3 && !STOP.contains(w) && !w.matches("\\d+")) {
                m.merge(w, 1, Integer::sum);
            }
        }
        return m;
    }

    private static double norm(Map<String, Integer> f) {
        double s = 0;
        for (int v : f.values()) {
            s += (double) v * v;
        }
        return Math.sqrt(s);
    }
}
