package ai.jobmatcher.jobs;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import ai.jobmatcher.fetch.SafeFetcher;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Arbeitnow's free public job-board API (https://www.arbeitnow.com/blog/job-board-api): no key, mostly German and
 * EU roles, English and German text. The API has no search parameters, so keyword and location filters are applied
 * locally. Its terms ask for fair use and a link back, which the UI provides through each offer's source URL.
 */
@Component
public class ArbeitnowAdapter implements JobSourceAdapter {

    static final String ENDPOINT = "https://www.arbeitnow.com/api/job-board-api";

    private final SafeFetcher fetcher;
    private final JsonMapper json;
    private final String endpoint;

    public ArbeitnowAdapter(SafeFetcher fetcher, JsonMapper json,
            @Value("${app.jobs.arbeitnow.endpoint:" + ENDPOINT + "}") String endpoint) {
        this.fetcher = fetcher;
        this.json = json;
        this.endpoint = endpoint;
    }

    @Override
    public String key() {
        return "arbeitnow";
    }

    @Override
    public String displayName() {
        return "Arbeitnow";
    }

    @Override
    public String termsUrl() {
        return "https://www.arbeitnow.com/blog/job-board-api";
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public List<RawJob> search(SearchQuery q) {
        List<RawJob> out = new ArrayList<>();
        String next = endpoint;
        for (int page = 0; page < 3 && next != null && out.size() < q.limit(); page++) {
            SafeFetcher.Response res = fetcher.get(next, "application/json", false);
            if (res.status() != 200) {
                throw new SafeFetcher.FetchException("Arbeitnow answered with HTTP " + res.status() + ".");
            }
            JsonNode root = json.readTree(res.body());
            for (JsonNode j : root.path("data")) {
                RawJob job = map(j);
                if (job != null
                        && q.matchesKeywords(job.title() + " " + job.company() + " " + job.description() + " " + j.path("tags"))
                        && q.matchesLocation(job.location()) && (!q.remoteOnly() || job.remote() == RemoteMode.REMOTE)) {
                    out.add(job);
                    if (out.size() >= q.limit()) {
                        break;
                    }
                }
            }
            String link = root.path("links").path("next").asString(null);
            next = link != null && link.startsWith(endpoint.contains("?") ? endpoint.substring(0, endpoint.indexOf('?')) : endpoint) ? link : null;
        }
        return out;
    }

    RawJob map(JsonNode j) {
        String slug = j.path("slug").asString("");
        String title = j.path("title").asString("");
        String url = j.path("url").asString("");
        if (slug.isBlank() || title.isBlank() || url.isBlank()) {
            return null;
        }
        long created = j.path("created_at").asLong(0);
        String type = j.path("job_types").isArray() && j.path("job_types").size() > 0
                ? j.path("job_types").get(0).asString(null) : null;
        String location = blankToNull(j.path("location").asString(null));
        // The source says remote either with its flag or by naming "Remote" as the location.
        boolean remote = j.path("remote").asBoolean(false) || (location != null && location.equalsIgnoreCase("remote"));
        return new RawJob(slug, title, blankToNull(j.path("company_name").asString(null)), location, null,
                remote ? RemoteMode.REMOTE : RemoteMode.UNKNOWN,
                HtmlText.toText(j.path("description").asString("")), url, url, blankToNull(type), null, null, null,
                created > 0 ? Instant.ofEpochSecond(created) : null, null);
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
