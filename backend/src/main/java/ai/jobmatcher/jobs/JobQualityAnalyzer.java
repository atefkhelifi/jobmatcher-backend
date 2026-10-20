package ai.jobmatcher.jobs;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Looks for patterns that are common in fraudulent or low-quality postings and reports each with the words that triggered it.
 * These are warnings, never verdicts: a legitimate ad can trip one, and a scam can trip none. Rules are plain patterns in
 * English and French so the result can be explained line by line.
 */
@Component
public class JobQualityAnalyzer {

    public enum Severity { CAUTION, NOTE }

    /** {@code evidence} is a short quote from the ad, or null when the warning is about something missing. */
    public record Warning(String code, Severity severity, String evidence) {
    }

    static final int MIN_DESCRIPTION = 250;
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private record Rule(String code, Severity severity, Pattern pattern) {
        Rule(String code, Severity severity, String regex) {
            this(code, severity, Pattern.compile(regex, FLAGS));
        }
    }

    private static final List<Rule> RULES = List.of(
            new Rule("FEE_REQUESTED", Severity.CAUTION,
                    "(?:registration|application|training|processing|administrat\\w*|onboarding|starter[- ]kit|security|deposit)\\s+(?:fee|payment|cost)s?\\b"
                            + "|\\bpay (?:a|an|the|your)?\\s*(?:small |one-time |upfront |refundable )?(?:fee|deposit)\\b"
                            + "|\\b(?:send|wire|transfer) (?:us )?(?:the )?money\\b|\\bbuy (?:your own )?(?:equipment|laptop|software|a starter)\\b"
                            + "|frais\\s+d['’ ]\\s*(?:inscription|dossier|formation|activation)|\\bvous devez (?:payer|r[ée]gler|verser)\\b"),
            new Rule("SENSITIVE_DOCUMENTS", Severity.CAUTION,
                    "\\b(?:send|provide|submit|share|envoyez|fournissez|transmettez)\\b[^.\\n]{0,60}\\b(?:passport|passeport|id card|identity card|carte d['’]identit[ée]|"
                            + "bank (?:account|details|statement)|relev[ée] bancaire|iban|\\brib\\b|social security|s[ée]curit[ée] sociale|ssn|credit card|carte bancaire)"),
            new Rule("MESSAGING_APP_CONTACT", Severity.CAUTION,
                    "\\b(?:whatsapp|telegram|wechat|signal|viber)\\b"),
            new Rule("UNREALISTIC_EARNINGS", Severity.CAUTION,
                    "\\beasy money\\b|\\bguaranteed (?:income|earnings|salary)\\b|\\bget rich\\b|\\bearn (?:up to )?[$€£]?\\s?\\d[\\d.,\\s]*\\s?(?:k\\s)?(?:[$€£]|usd|eur)?\\s*(?:per|a|/)\\s*(?:day|hour|week)\\b"
                            + "|\\bno experience (?:needed|required|necessary)\\b[^.\\n]{0,80}\\b(?:[$€£]\\s?\\d{3,}|\\d{3,}\\s?[$€£])"
                            + "|\\bgagnez (?:jusqu['’]à )?\\d[\\d\\s.,]*\\s?(?:[$€£]|euros?)\\s*(?:par|/)\\s*(?:jour|semaine|heure)|\\brevenus? garantis?\\b|\\bargent facile\\b"),
            new Rule("URGENCY_PRESSURE", Severity.NOTE,
                    "\\burgent(?:ly)? (?:hiring|recruitment|need|vacancy)\\b|\\bhiring (?:immediately|now|asap)\\b|\\blimited (?:spots|positions|places|slots)\\b|"
                            + "\\bapply (?:now|immediately|today) (?:before|or)\\b|\\bplaces limit[ée]es\\b|\\brecrutement urgent\\b|\\bpostes? [àa] pourvoir imm[ée]diatement\\b"),
            new Rule("PERSONAL_EMAIL_CONTACT", Severity.NOTE,
                    "[\\w.+-]+@(?:gmail|yahoo|hotmail|outlook|live|icloud|proton(?:mail)?|aol|gmx|yandex|mail)\\.[a-z.]{2,}"));

    public List<Warning> analyze(String title, String company, String description) {
        List<Warning> out = new ArrayList<>();
        String text = ((title == null ? "" : title) + "\n" + (description == null ? "" : description));
        for (Rule r : RULES) {
            Matcher m = r.pattern().matcher(text);
            if (m.find()) {
                out.add(new Warning(r.code(), r.severity(), quote(text, m.start(), m.end())));
            }
        }
        if (company == null || company.isBlank()) {
            out.add(new Warning("NO_COMPANY", Severity.NOTE, null));
        }
        if (description == null || description.strip().length() < MIN_DESCRIPTION) {
            out.add(new Warning("VERY_SHORT", Severity.NOTE, null));
        }
        out.sort((a, b) -> a.severity().compareTo(b.severity()));
        return out;
    }

    /** The matched words with a little context, on one line. */
    static String quote(String text, int start, int end) {
        int from = Math.max(0, start - 30);
        int to = Math.min(text.length(), end + 30);
        String q = text.substring(from, to).replaceAll("\\s+", " ").strip();
        return (from > 0 ? "…" : "") + q + (to < text.length() ? "…" : "");
    }
}
