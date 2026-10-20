package ai.jobmatcher.jobs;

/** What the user asked for. Adapters apply whatever their source supports and the rest is filtered locally. */
public record SearchQuery(String keywords, String location, boolean remoteOnly, int limit) {

    public static SearchQuery of(String keywords, String location, boolean remoteOnly) {
        return new SearchQuery(keywords == null ? "" : keywords.trim(), location == null ? "" : location.trim(), remoteOnly, 100);
    }

    /** True when every keyword appears in {@code haystack} (case-insensitive). Empty keywords match everything. */
    public boolean matchesKeywords(String haystack) {
        if (keywords.isBlank()) {
            return true;
        }
        String h = haystack.toLowerCase(java.util.Locale.ROOT);
        for (String k : keywords.toLowerCase(java.util.Locale.ROOT).split("\s+")) {
            if (!k.isBlank() && !h.contains(k)) {
                return false;
            }
        }
        return true;
    }

    public boolean matchesLocation(String locationText) {
        return location.isBlank() || (locationText != null && locationText.toLowerCase(java.util.Locale.ROOT)
                .contains(location.toLowerCase(java.util.Locale.ROOT)));
    }
}
