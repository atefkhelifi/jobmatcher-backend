package ai.jobmatcher.fetch;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The only way application code reaches the internet. It enforces the SSRF guard on every hop, caps response size
 * and time, spaces out requests to the same host, and (for HTML pages) honours robots.txt. It never solves
 * CAPTCHAs, logs in, or retries around a refusal.
 */
@Component
public class SafeFetcher {

    public static final String USER_AGENT = "JobMatcherBot/0.1 (+https://github.com/jobmatcher-ai; personal job search tool)";
    private static final Logger log = LoggerFactory.getLogger(SafeFetcher.class);
    private static final int MAX_REDIRECTS = 3;

    public record Response(int status, String body, URI finalUri) {
    }

    public static final class FetchException extends RuntimeException {
        public FetchException(String message) {
            super(message);
        }

        public FetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final UrlGuard guard;
    private final HttpClient client;
    private final long maxBytes;
    private final Duration timeout;
    private final long minIntervalMs;
    private final Map<String, Long> lastRequest = new ConcurrentHashMap<>();
    private final Map<String, RobotsEntry> robotsCache = new ConcurrentHashMap<>();

    private record RobotsEntry(RobotsTxt rules, long fetchedAt) {
    }

    public SafeFetcher(@Value("${app.fetch.allow-private:false}") boolean allowPrivate,
            @Value("${app.fetch.max-bytes:5242880}") long maxBytes,
            @Value("${app.fetch.timeout:15s}") Duration timeout,
            @Value("${app.fetch.min-interval:1s}") Duration minInterval) {
        this.guard = new UrlGuard(allowPrivate);
        this.maxBytes = maxBytes;
        this.timeout = timeout;
        this.minIntervalMs = minInterval.toMillis();
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** Fetches a document or API response. Set {@code checkRobots} for ordinary web pages. */
    public Response get(String url, String accept, boolean checkRobots) {
        return get(url, accept, checkRobots, Map.of());
    }

    public Response get(String url, String accept, boolean checkRobots, Map<String, String> headers) {
        return execute("GET", url, accept, checkRobots, headers, null);
    }

    public Response postForm(String url, String formBody, Map<String, String> headers) {
        return execute("POST", url, "application/json", false, headers, formBody);
    }

    private Response execute(String method, String url, String accept, boolean checkRobots, Map<String, String> headers,
            String body) {
        URI uri = guarded(url);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (checkRobots && !robotsAllow(uri)) {
                throw new FetchException("The site's robots.txt does not allow automated access to this page.");
            }
            throttle(uri.getHost());
            HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout).header("User-Agent", USER_AGENT)
                    .header("Accept", accept);
            headers.forEach(b::header);
            if (body != null) {
                b.header("Content-Type", "application/x-www-form-urlencoded");
                b.POST(HttpRequest.BodyPublishers.ofString(body));
            } else {
                b.GET();
            }
            HttpResponse<InputStream> res;
            try {
                res = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                throw new FetchException("The site could not be reached.", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FetchException("The request was interrupted.");
            }
            int status = res.statusCode();
            if (status >= 300 && status < 400) {
                closeQuietly(res.body());
                String location = res.headers().firstValue("Location").orElse(null);
                if (location == null || hop == MAX_REDIRECTS) {
                    throw new FetchException("Too many redirects.");
                }
                uri = guarded(uri.resolve(location).toString());
                body = null; // a redirected POST becomes a GET; credentials are never forwarded
                method = "GET";
                continue;
            }
            return new Response(status, readCapped(res.body()), uri);
        }
        throw new FetchException("Too many redirects.");
    }

    private URI guarded(String url) {
        try {
            return guard.check(url);
        } catch (UrlGuard.Blocked e) {
            throw new FetchException(e.getMessage(), e);
        }
    }

    private String readCapped(InputStream in) {
        try (in) {
            byte[] data = in.readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE));
            if (data.length > maxBytes) {
                throw new FetchException("The response is too large.");
            }
            return new String(data, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new FetchException("The response could not be read.", e);
        }
    }

    private void throttle(String host) {
        long wait;
        synchronized (lastRequest) {
            long now = System.currentTimeMillis();
            long next = lastRequest.getOrDefault(host, 0L) + minIntervalMs;
            wait = Math.max(0, next - now);
            lastRequest.put(host, now + wait);
        }
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean robotsAllow(URI uri) {
        String origin = uri.getScheme() + "://" + uri.getAuthority();
        long now = System.currentTimeMillis();
        RobotsEntry cached = robotsCache.get(origin);
        if (cached == null || now - cached.fetchedAt() > Duration.ofHours(1).toMillis()) {
            RobotsTxt rules;
            try {
                URI robots = guard.check(origin + "/robots.txt");
                throttle(robots.getHost());
                HttpResponse<InputStream> res = client.send(HttpRequest.newBuilder(robots).timeout(timeout)
                        .header("User-Agent", USER_AGENT).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                int s = res.statusCode();
                String text = readCapped(res.body());
                // 4xx: no robots file, so no restrictions. 5xx: be conservative and stay out.
                rules = s >= 500 ? RobotsTxt.disallowAll() : s >= 400 ? RobotsTxt.allowAll() : RobotsTxt.parse(text, "JobMatcherBot");
            } catch (IOException | UrlGuard.Blocked | FetchException e) {
                log.debug("robots.txt unavailable for {}", origin);
                rules = RobotsTxt.disallowAll();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                rules = RobotsTxt.disallowAll();
            }
            cached = new RobotsEntry(rules, now);
            robotsCache.put(origin, cached);
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return cached.rules().allows(uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery());
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
