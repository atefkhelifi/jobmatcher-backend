package ai.jobmatcher.jobs;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ai.jobmatcher.fetch.SafeFetcher;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Keejob (Tunisia) through the RSS feed it publishes for the latest offers (https://www.keejob.com/rss/jobs/, also listed in its
 * llms.txt). A feed is meant for being read by other programs, and the site's robots.txt allows it. The feed carries the title,
 * the company, the description and the date; fields it does not carry (salary, contract) stay unknown. Every offer links back to
 * its Keejob page, where the candidate applies.
 */
@Component
public class KeejobAdapter implements JobSourceAdapter {

    static final String FEED = "https://www.keejob.com/rss/jobs/";
    private static final Pattern ID = Pattern.compile("/offres-emploi/(\\d+)/");
    private static final Pattern COMPANY = Pattern.compile("Entre[a-z]*ise\\s*:\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLACE = Pattern.compile("Lieu\\s*:\\s*([^\\n]{2,80})", Pattern.CASE_INSENSITIVE);

    private final SafeFetcher fetcher;
    private final String feed;
    private final boolean enabled;

    public KeejobAdapter(SafeFetcher fetcher, @Value("${app.jobs.keejob.feed:" + FEED + "}") String feed,
            @Value("${app.jobs.keejob.enabled:true}") boolean enabled) {
        this.fetcher = fetcher;
        this.feed = feed;
        this.enabled = enabled;
    }

    @Override
    public String key() {
        return "keejob";
    }

    @Override
    public String displayName() {
        return "Keejob (Tunisia)";
    }

    @Override
    public String termsUrl() {
        return "https://www.keejob.com/";
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public String disabledReason() {
        return "Switched off with APP_JOBS_KEEJOB_ENABLED=false.";
    }

    @Override
    public List<RawJob> search(SearchQuery q) {
        SafeFetcher.Response res = fetcher.get(feed, "application/rss+xml, application/xml;q=0.9, */*;q=0.5", true);
        if (res.status() != 200) {
            throw new SafeFetcher.FetchException("Keejob answered with HTTP " + res.status() + ".");
        }
        List<RawJob> out = new ArrayList<>();
        for (RawJob job : parse(res.body())) {
            if (q.matchesKeywords(job.title() + " " + job.company() + " " + job.description()) && q.matchesLocation(job.location() == null ? "" : job.location())
                    && (!q.remoteOnly() || job.remote() == RemoteMode.REMOTE)) {
                out.add(job);
                if (out.size() >= q.limit()) {
                    break;
                }
            }
        }
        return out;
    }

    static List<RawJob> parse(String xml) {
        Document doc = Jsoup.parse(xml, "", Parser.xmlParser());
        List<RawJob> out = new ArrayList<>();
        for (Element item : doc.select("item")) {
            String title = item.selectFirst("title") == null ? "" : item.selectFirst("title").text().strip();
            String link = item.selectFirst("link") == null ? "" : item.selectFirst("link").text().strip();
            Matcher id = ID.matcher(link);
            if (title.isBlank() || !link.startsWith("https://") || !id.find()) {
                continue;
            }
            String html = item.selectFirst("description") == null ? "" : item.selectFirst("description").text();
            String text = HtmlText.toText(html);
            String company = null;
            Matcher c = COMPANY.matcher(text);
            if (c.find()) {
                company = c.group(1).strip();
                text = text.replace(c.group(0), "").strip();
            }
            Matcher p = PLACE.matcher(text);
            String place = p.find() ? p.group(1).replaceAll("[\\p{So}\\p{Cn}]", "").strip() : null;
            out.add(new RawJob(id.group(1), title, ArbeitnowAdapter.blankToNull(company), ArbeitnowAdapter.blankToNull(place), "TN",
                    RemoteMode.UNKNOWN, text, link, link, null, null, null, null, date(item), null));
        }
        return out;
    }

    private static Instant date(Element item) {
        Element d = item.selectFirst("pubDate");
        if (d == null) {
            return null;
        }
        try {
            return ZonedDateTime.parse(d.text().strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
