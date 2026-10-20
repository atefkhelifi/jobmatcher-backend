package ai.jobmatcher.jobs;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import ai.jobmatcher.fetch.SafeFetcher;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Greenhouse's public Job Board API, which employers publish on purpose so their openings can be listed
 * (https://developers.greenhouse.io/job-board.html). It reads only the boards named in {@code app.jobs.greenhouse-boards}.
 */
@Component
public class GreenhouseAdapter implements JobSourceAdapter {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{1,60}");

    private final SafeFetcher fetcher;
    private final JsonMapper json;
    private final List<String> boards;
    private final String base;

    public GreenhouseAdapter(SafeFetcher fetcher, JsonMapper json,
            @Value("${app.jobs.greenhouse-boards:}") List<String> boards,
            @Value("${app.jobs.greenhouse.base:https://boards-api.greenhouse.io}") String base) {
        this.fetcher = fetcher;
        this.json = json;
        this.boards = boards.stream().map(String::trim).filter(b -> TOKEN.matcher(b).matches()).toList();
        this.base = base;
    }

    @Override
    public String key() {
        return "greenhouse";
    }

    @Override
    public String displayName() {
        return "Company boards (Greenhouse)";
    }

    @Override
    public String termsUrl() {
        return "https://developers.greenhouse.io/job-board.html";
    }

    @Override
    public boolean enabled() {
        return !boards.isEmpty();
    }

    @Override
    public String disabledReason() {
        return "Set APP_JOBS_GREENHOUSE_BOARDS to a comma-separated list of company board names, for example gitlab,stripe.";
    }

    @Override
    public List<RawJob> search(SearchQuery q) {
        List<RawJob> out = new ArrayList<>();
        for (String board : boards) {
            SafeFetcher.Response res = fetcher.get(
                    base + "/v1/boards/" + URLEncoder.encode(board, StandardCharsets.UTF_8) + "/jobs?content=true",
                    "application/json", false);
            if (res.status() == 404) {
                continue; // this company has no public board; other boards may still work
            }
            if (res.status() != 200) {
                throw new SafeFetcher.FetchException("Greenhouse answered with HTTP " + res.status() + " for board " + board + ".");
            }
            for (JsonNode j : json.readTree(res.body()).path("jobs")) {
                RawJob job = map(board, j);
                if (job != null && q.matchesKeywords(job.title() + " " + job.description()) && q.matchesLocation(job.location())
                        && (!q.remoteOnly() || job.remote() == RemoteMode.REMOTE)) {
                    out.add(job);
                }
            }
        }
        return out.size() > q.limit() ? out.subList(0, q.limit()) : out;
    }

    RawJob map(String board, JsonNode j) {
        String id = j.path("id").asString("");
        String title = j.path("title").asString("");
        String url = j.path("absolute_url").asString("");
        if (id.isBlank() || title.isBlank() || url.isBlank()) {
            return null;
        }
        String location = ArbeitnowAdapter.blankToNull(j.path("location").path("name").asString(null));
        boolean remote = location != null && location.toLowerCase(Locale.ROOT).contains("remote");
        String company = ArbeitnowAdapter.blankToNull(j.path("company_name").asString(null));
        return new RawJob(board + ":" + id, title, company != null ? company : board, location, null,
                remote ? RemoteMode.REMOTE : RemoteMode.UNKNOWN, HtmlText.toText(j.path("content").asString("")), url, url,
                null, null, null, null, instant(j.path("first_published").asString(null)), null);
    }

    private static Instant instant(String iso) {
        try {
            return iso == null ? null : OffsetDateTime.parse(iso).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
