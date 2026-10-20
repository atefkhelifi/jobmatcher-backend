package ai.jobmatcher.matching;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import ai.jobmatcher.cv.SkillCatalog;

import org.springframework.stereotype.Component;

/**
 * Finds the skills an ad asks for and decides whether each is required or preferred, from section headings
 * ("Requirements", "Nice to have", "Profil recherché") and cue words on the line itself ("must", "un plus"). A skill the ad
 * merely mentions, with neither signal, is reported as mentioned and classified later.
 */
@Component
public class JobSkillExtractor {

    public enum Level { REQUIRED, PREFERRED, MENTIONED }

    public record JobSkill(String skill, Level level, int mentions) {
    }

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern REQ_HEAD = Pattern.compile(
            "^(?:requirements?|required(?: skills| qualifications)?|must[- ]haves?|(?:minimum |basic )?qualifications|what you(?:'ll| will)? (?:bring|need)|"
                    + "your profile|who you are|about you|skills(?: and experience)?|profil(?:e)? recherch[ée]|exigences|comp[ée]tences(?: requises| techniques)?|"
                    + "vous avez|votre profil|le profil|pr[ée]requis|ce que vous apportez|indispensable|vous [êe]tes)\\b.*",
            FLAGS);
    private static final Pattern PREF_HEAD = Pattern.compile(
            "^(?:nice[- ]to[- ]haves?|preferred(?: qualifications| skills)?|bonus(?: points)?|desirable|good to have|pluses|a plus|"
                    + "souhait[ée]s?|atouts?|appr[ée]ci[ée]s?|un plus|serait un plus|id[ée]alement)\\b.*",
            FLAGS);
    private static final Pattern OTHER_HEAD = Pattern.compile(
            "^(?:responsibilit(?:y|ies)|what you(?:'ll| will) do|your role|the role|about (?:us|the (?:role|job|company|team))|benefits|we offer|"
                    + "what we offer|perks|tasks|duties|missions?|vos missions|le poste|ce que nous offrons|avantages|notre offre|[àa] propos|"
                    + "description|company|our (?:stack|tech stack)|tech stack|la mission|l'entreprise)\\b.*",
            FLAGS);
    private static final Pattern PREF_CUE = Pattern.compile(
            "nice[- ]to[- ]have|is a plus|are a plus|a plus\\b|preferred|bonus|desirable|good to have|souhait[ée]|appr[ée]ci[ée]|un plus|"
                    + "id[ée]alement|de pr[ée]f[ée]rence|serait un atout|would be (?:an )?(?:asset|advantage)|not required|optional",
            FLAGS);
    private static final Pattern REQ_CUE = Pattern.compile(
            "\\bmust\\b|\\brequired\\b|mandatory|essential|requis|indispensable|obligatoire|exig[ée]|imp[ée]ratif|\\bma[iî]trise\\b|"
                    + "(?:solid|strong|proven|extensive|deep) (?:experience|knowledge|background)|exp[ée]rience (?:solide|confirm[ée]e|significative)|"
                    + "\\d+\\s*\\+?\\s*(?:years?|yrs?|ans|ann[ée]es)",
            FLAGS);

    private final SkillCatalog catalog;

    public JobSkillExtractor(SkillCatalog catalog) {
        this.catalog = catalog;
    }

    public List<JobSkill> extract(String title, String description) {
        Map<String, Level> levels = new LinkedHashMap<>();
        Map<String, Integer> mentions = new LinkedHashMap<>();
        Map<String, SkillCatalog.Skill> canonical = new LinkedHashMap<>();

        // A skill named in the job title is the role itself, so it is required.
        for (SkillCatalog.Hit h : catalog.find(title == null ? "" : title)) {
            record(levels, mentions, canonical, h, Level.REQUIRED);
        }

        Level section = Level.MENTIONED;
        for (String raw : (description == null ? "" : description).split("\\R")) {
            String line = raw.trim().replaceFirst("^[•*\\-–—▪●◦·\\d.)\\s]+", "");
            if (line.isEmpty()) {
                continue;
            }
            Level headed = heading(line);
            if (headed != null) {
                section = headed;
                if (line.length() <= 60) {
                    continue; // a bare heading carries no skills of its own
                }
            }
            Level lineLevel = PREF_CUE.matcher(line).find() ? Level.PREFERRED
                    : REQ_CUE.matcher(line).find() ? Level.REQUIRED : section;
            for (SkillCatalog.Hit h : catalog.find(line)) {
                record(levels, mentions, canonical, h, lineLevel);
            }
        }
        return levels.entrySet().stream()
                .map(e -> new JobSkill(canonical.get(e.getKey()).canonical(), e.getValue(), mentions.get(e.getKey()))).toList();
    }

    private static Level heading(String line) {
        String t = line.replaceAll("[:：]\\s*$", "").trim();
        if (t.length() > 80 || (t.endsWith(".") && t.length() > 25)) {
            return null;
        }
        if (PREF_HEAD.matcher(t).matches()) {
            return Level.PREFERRED;
        }
        if (REQ_HEAD.matcher(t).matches()) {
            return Level.REQUIRED;
        }
        if (OTHER_HEAD.matcher(t).matches()) {
            return Level.MENTIONED;
        }
        return null;
    }

    private static void record(Map<String, Level> levels, Map<String, Integer> mentions,
            Map<String, SkillCatalog.Skill> canonical, SkillCatalog.Hit h, Level level) {
        String key = h.skill().canonical().toLowerCase(Locale.ROOT);
        canonical.putIfAbsent(key, h.skill());
        mentions.merge(key, 1, Integer::sum);
        levels.merge(key, level, (a, b) -> a.ordinal() <= b.ordinal() ? a : b); // REQUIRED beats PREFERRED beats MENTIONED
    }
}
