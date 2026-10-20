package ai.jobmatcher.jobs;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs a query against the chosen sources. One failing source never stops the others; its failure is recorded in
 * the source's health row and reported back so the user sees exactly what happened.
 */
@Service
public class DiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryService.class);

    public record SourceRun(String source, int fetched, int created, int updated, int duplicates, String error) {
    }

    public record RunResult(List<SourceRun> sources, int created, int updated, int duplicates) {
    }

    private final List<JobSourceAdapter> adapters;
    private final OfferIngestService ingest;
    private final SourceHealthRepository health;

    DiscoveryService(List<JobSourceAdapter> adapters, OfferIngestService ingest, SourceHealthRepository health) {
        this.adapters = adapters;
        this.ingest = ingest;
        this.health = health;
    }

    public List<JobSourceAdapter> adapters() {
        return adapters;
    }

    /** @param sourceKeys empty means every enabled source */
    public RunResult run(SearchQuery query, Set<String> sourceKeys) {
        List<SourceRun> runs = new ArrayList<>();
        int created = 0;
        int updated = 0;
        int duplicates = 0;
        for (JobSourceAdapter a : adapters) {
            if (!a.enabled() || (!sourceKeys.isEmpty() && !sourceKeys.contains(a.key()))) {
                continue;
            }
            SourceHealth h = health.findById(a.key()).orElseGet(() -> new SourceHealth(a.key()));
            try {
                List<RawJob> raws = a.search(query);
                OfferIngestService.Result r = ingest.ingest(a.key(), null, raws);
                h.recordSuccess(raws.size());
                runs.add(new SourceRun(a.key(), raws.size(), r.created(), r.updated(), r.duplicates(), null));
                created += r.created();
                updated += r.updated();
                duplicates += r.duplicates();
            } catch (RuntimeException e) {
                log.warn("Source {} failed: {}", a.key(), e.getClass().getSimpleName());
                String msg = e instanceof ai.jobmatcher.fetch.SafeFetcher.FetchException ? e.getMessage() : "The source returned data this app could not read.";
                h.recordFailure(msg);
                runs.add(new SourceRun(a.key(), 0, 0, 0, 0, msg));
            }
            health.save(h);
        }
        return new RunResult(List.copyOf(runs), created, updated, duplicates);
    }
}
