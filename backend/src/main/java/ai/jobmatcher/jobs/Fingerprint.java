package ai.jobmatcher.jobs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Identity of a role independent of the source: the same title, company and city posted on two boards produces the
 * same fingerprint. Accents, case, punctuation and common gender/contract suffixes such as "(m/f/d)" are ignored.
 */
public final class Fingerprint {

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern GENDER = Pattern.compile("\\((?:m|f|w|d|h|x|/|\\s|\\*|-)+\\)|\\b[mfhwdx]/[mfhwdx](?:/[mfhwdx])?\\b");
    private static final Pattern LEGAL_FORM = Pattern.compile(
            "\\s(?:gmbh|ag|se|kg|ug|co|und|sas|sarl|sa|sasu|eurl|snc|ltd|limited|llc|inc|incorporated|corp|corporation|plc|bv|nv|srl|spa|ab|as|oy|pty|gmbh co kg)$");
    private static final Pattern NOISE = Pattern.compile("[^a-z0-9]+");

    private Fingerprint() {
    }

    public static String of(String title, String company, String location) {
        String canonical = norm(title) + "|" + company(company) + "|" + norm(city(location));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Company names differ across sources only by legal form ("Acme", "Acme GmbH", "ACME Inc."), so trailing legal forms are dropped. */
    public static String company(String name) {
        String n = norm(name == null ? null : name.replace(".", "")); // "S.A.S." and "SAS" are the same form
        String stripped = n;
        while (true) {
            String next = LEGAL_FORM.matcher(stripped).replaceFirst("").trim();
            if (next.equals(stripped) || next.isEmpty()) {
                break;
            }
            stripped = next;
        }
        return stripped;
    }

    static String city(String location) {
        if (location == null) {
            return "";
        }
        int comma = location.indexOf(',');
        return comma > 0 ? location.substring(0, comma) : location;
    }

    static String norm(String s) {
        if (s == null) {
            return "";
        }
        String t = DIACRITICS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT);
        t = GENDER.matcher(t).replaceAll(" ");
        return NOISE.matcher(t).replaceAll(" ").trim();
    }
}
