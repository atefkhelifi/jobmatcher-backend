package ai.jobmatcher.jobs;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ai.jobmatcher.fetch.SafeFetcher;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Tanitjobs (Tunisia). The site has no feed or API, so this adapter reads what any visitor sees: the public list of latest jobs,
 * then each job page's own structured data (schema.org JobPosting), the same way the "import a link" feature does.
 *
 * <p>It is <b>off by default</b> and meant for one person's own job search. Tanitjobs' terms (https://www.tanitjobs.com/terms-of-use/)
 * allow viewing its content "for your personal use and for non-commercial purposes" and forbid overloading the site. So:
 * robots.txt is checked for every page, the site's keyword search is never used (robots.txt disallows it; filtering is done here),
 * at most {@code max-offers} pages are fetched per run, and the fetcher spaces requests to the same host. Do not enable it on a
 * shared or commercial deployment without the site owner's agreement.
 */
@Component
public class TanitjobsAdapter implements JobSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(TanitjobsAdapter.class);
    static final String BASE = "https://www.tanitjobs.com";
    private static final Pattern JOB_PATH = Pattern.compile("^/job/(\\d+)/");

    private final SafeFetcher fetcher;
    private final JobPostingParser parser;
    private final boolean enabled;
    private final String base;
    private final int maxOffers;

    public TanitjobsAdapter(SafeFetcher fetcher, JobPostingParser parser, @Value("${app.jobs.tanitjobs.enabled:false}") boolean enabled,
            @Value("${app.jobs.tanitjobs.base:" + BASE + "}") String base, @Value("${app.jobs.tanitjobs.max-offers:25}") int maxOffers) {
        this.fetcher = fetcher;
        this.parser = parser;
        this.enabled = enabled;
        this.base = base;
        this.maxOffers = Math.max(1, Math.min(maxOffers, 50));
    }

    @Override
    public String key() {
        return "tanitjobs";
    }

    @Override
    public String displayName() {
        return "Tanitjobs (Tunisia)";
    }

    @Override
    public String termsUrl() {
        return "https://www.tanitjobs.com/terms-of-use/";
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public String disabledReason() {
        return "Off by default: its terms allow personal, non-commercial use only. Set APP_JOBS_TANITJOBS_ENABLED=true for your own instance.";
    }

    @Override
    public List<RawJob> search(SearchQuery q) {
        SafeFetcher.Response list = fetcher.get(base + "/jobs/", "text/html", true);
        if (list.status() != 200) {
            throw new SafeFetcher.FetchException("Tanitjobs answered with HTTP " + list.status() + ".");
        }
        List<RawJob> out = new ArrayList<>();
        for (String url : jobLinks(list.body(), list.finalUri())) {
            if (out.size() >= Math.min(q.limit(), maxOffers)) {
                break;
            }
            try {
                SafeFetcher.Response page = fetcher.get(url, "text/html", true);
                if (page.status() != 200) {
                    continue;
                }
                Optional<RawJob> parsed = parser.parse(page.body(), page.finalUri());
                parsed.map(TanitjobsAdapter::inTunisia).filter(j -> q.matchesKeywords(j.title() + " " + j.company() + " " + j.description())
                        && q.matchesLocation(j.location() == null ? "" : j.location()) && (!q.remoteOnly() || j.remote() == RemoteMode.REMOTE))
                        .ifPresent(out::add);
            } catch (SafeFetcher.FetchException e) {
                log.debug("Skipping one Tanitjobs page: {}", e.getMessage()); // robots.txt or a network error on one page
            }
        }
        return out;
    }

    /** The job pages linked from the list, newest first, without repeats. */
    static List<String> jobLinks(String html, URI listUri) {
        Document doc = Jsoup.parse(html, listUri.toString());
        Set<String> seen = new LinkedHashSet<>();
        List<String> urls = new ArrayList<>();
        for (Element a : doc.select("a[href]")) {
            String href = a.absUrl("href");
            if (href.isBlank()) {
                continue;
            }
            URI u;
            try {
                u = URI.create(href);
            } catch (IllegalArgumentException e) {
                continue;
            }
            Matcher m = JOB_PATH.matcher(u.getRawPath() == null ? "" : u.getRawPath());
            if (u.getHost() != null && u.getHost().equals(listUri.getHost()) && m.find() && seen.add(m.group(1))) {
                urls.add(u.getScheme() + "://" + u.getRawAuthority() + u.getRawPath());
            }
        }
        return urls;
    }

    private static RawJob inTunisia(RawJob j) {
        if (j.country() != null) {
            return j;
        }
        return new RawJob(j.externalId(), j.title(), j.company(), j.location(), "TN", j.remote(), j.description(), j.url(), j.applyUrl(),
                j.employmentType(), j.salaryMin(), j.salaryMax(), j.salaryCurrency(), j.postedAt(), j.validThrough());
    }
}
