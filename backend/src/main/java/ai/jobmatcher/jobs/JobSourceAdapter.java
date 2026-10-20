package ai.jobmatcher.jobs;

import java.util.List;

/**
 * One job source. Adding a source means implementing this interface and registering it as a Spring bean;
 * nothing else changes. Implementations must use {@link ai.jobmatcher.fetch.SafeFetcher} for all network access
 * and must only use endpoints whose terms allow this kind of use.
 */
public interface JobSourceAdapter {

    /** Stable id stored with every offer, e.g. "arbeitnow". */
    String key();

    String displayName();

    /** Where the source's terms of use can be read. Shown in the UI. */
    String termsUrl();

    /** False when the source needs configuration (API credentials, board list) that is missing. */
    boolean enabled();

    /** Why the source is disabled, shown to the user. */
    default String disabledReason() {
        return "";
    }

    List<RawJob> search(SearchQuery query);
}
