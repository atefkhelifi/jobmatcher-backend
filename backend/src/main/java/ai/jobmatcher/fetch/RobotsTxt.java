package ai.jobmatcher.fetch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Minimal robots.txt evaluation: longest matching rule wins, Allow beats Disallow on ties, "*" and "$" supported. */
public final class RobotsTxt {

    private record Rule(boolean allow, String pattern) {
    }

    private final List<Rule> rules;

    private RobotsTxt(List<Rule> rules) {
        this.rules = rules;
    }

    public static RobotsTxt allowAll() {
        return new RobotsTxt(List.of());
    }

    public static RobotsTxt disallowAll() {
        return new RobotsTxt(List.of(new Rule(false, "/")));
    }

    /** Parses the group that names {@code agent}; falls back to the "*" group. */
    public static RobotsTxt parse(String body, String agent) {
        String me = agent.toLowerCase(Locale.ROOT);
        List<Rule> specific = new ArrayList<>();
        List<Rule> wildcard = new ArrayList<>();
        boolean specificSeen = false;
        List<String> currentAgents = new ArrayList<>();
        boolean lastWasAgent = false;
        for (String raw : body.split("\\R")) {
            String line = raw.contains("#") ? raw.substring(0, raw.indexOf('#')) : raw;
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (key.equals("user-agent")) {
                if (!lastWasAgent) {
                    currentAgents = new ArrayList<>();
                }
                currentAgents.add(value.toLowerCase(Locale.ROOT));
                lastWasAgent = true;
                if (currentAgents.stream().anyMatch(a -> !a.equals("*") && me.contains(a))) {
                    specificSeen = true;
                }
                continue;
            }
            lastWasAgent = false;
            if (!key.equals("allow") && !key.equals("disallow")) {
                continue;
            }
            boolean allow = key.equals("allow");
            if (value.isEmpty()) {
                continue; // an empty Disallow means "allow everything"
            }
            if (currentAgents.stream().anyMatch(a -> !a.equals("*") && me.contains(a))) {
                specific.add(new Rule(allow, value));
            } else if (currentAgents.contains("*")) {
                wildcard.add(new Rule(allow, value));
            }
        }
        return new RobotsTxt(specificSeen ? specific : wildcard);
    }

    public boolean allows(String pathAndQuery) {
        Rule best = null;
        int bestLen = -1;
        for (Rule r : rules) {
            if (matches(r.pattern(), pathAndQuery)) {
                int len = r.pattern().length();
                if (len > bestLen || (len == bestLen && r.allow())) {
                    best = r;
                    bestLen = len;
                }
            }
        }
        return best == null || best.allow();
    }

    private static boolean matches(String pattern, String path) {
        boolean anchored = pattern.endsWith("$");
        String p = anchored ? pattern.substring(0, pattern.length() - 1) : pattern;
        StringBuilder regex = new StringBuilder();
        for (char c : p.toCharArray()) {
            regex.append(c == '*' ? ".*" : java.util.regex.Pattern.quote(String.valueOf(c)));
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex.toString(), java.util.regex.Pattern.DOTALL).matcher(path);
        return anchored ? m.matches() : m.lookingAt();
    }
}
