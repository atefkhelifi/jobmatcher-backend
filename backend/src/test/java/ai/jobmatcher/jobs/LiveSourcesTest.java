package ai.jobmatcher.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import ai.jobmatcher.fetch.SafeFetcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/** Hits the real public APIs. Opt in with RUN_LIVE_TESTS=true; never part of the default build. */
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_TESTS", matches = "true")
class LiveSourcesTest {

    @Test
    void arbeitnowReturnsRealNormalisedOffers() {
        SafeFetcher fetcher = new SafeFetcher(false, 5_242_880, Duration.ofSeconds(30), Duration.ofSeconds(1));
        ArbeitnowAdapter adapter = new ArbeitnowAdapter(fetcher, JsonMapper.builder().build(), ArbeitnowAdapter.ENDPOINT);

        List<RawJob> jobs = adapter.search(SearchQuery.of("", "", false));

        assertThat(jobs).hasSizeGreaterThan(20);
        assertThat(jobs).allSatisfy(j -> {
            assertThat(j.title()).isNotBlank();
            assertThat(j.url()).startsWith("https://");
            assertThat(j.description()).isNotBlank().doesNotContain("<p>").doesNotContain("&lt;");
        });
        assertThat(jobs.stream().filter(j -> j.postedAt() != null).count()).isGreaterThan(jobs.size() / 2);
    }
}
