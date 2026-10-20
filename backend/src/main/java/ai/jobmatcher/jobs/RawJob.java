package ai.jobmatcher.jobs;

import java.time.Instant;

/** A posting as normalised by an adapter, before it is stored. Unknown values are {@code null}. */
public record RawJob(
        String externalId,
        String title,
        String company,
        String location,
        String country,
        RemoteMode remote,
        String description,
        String url,
        String applyUrl,
        String employmentType,
        Integer salaryMin,
        Integer salaryMax,
        String salaryCurrency,
        Instant postedAt,
        Instant validThrough) {
}
