package ai.jobmatcher.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SafeFetcherTest {

    private static HttpServer server;
    private static String base;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/robots.txt", ex -> reply(ex, 200, "User-agent: *\nDisallow: /private\n"));
        server.createContext("/public", ex -> reply(ex, 200, "hello"));
        server.createContext("/private", ex -> reply(ex, 200, "secret"));
        server.createContext("/big", ex -> reply(ex, 200, "x".repeat(2000)));
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "/public");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/loop", ex -> {
            ex.getResponseHeaders().add("Location", "/loop");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/gone", ex -> reply(ex, 410, "gone"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private SafeFetcher open() {
        return new SafeFetcher(true, 1000, Duration.ofSeconds(5), Duration.ZERO);
    }

    @Test
    void fetchesAndFollowsRedirects() {
        SafeFetcher.Response r = open().get(base + "/redirect", "text/plain", false);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("hello");
        assertThat(r.finalUri().getPath()).isEqualTo("/public");
    }

    @Test
    void reportsErrorStatusesWithoutThrowing() {
        assertThat(open().get(base + "/gone", "text/plain", false).status()).isEqualTo(410);
    }

    @Test
    void stopsRedirectLoops() {
        assertThatThrownBy(() -> open().get(base + "/loop", "text/plain", false)).isInstanceOf(SafeFetcher.FetchException.class)
                .hasMessageContaining("redirects");
    }

    @Test
    void capsResponseSize() {
        assertThatThrownBy(() -> open().get(base + "/big", "text/plain", false)).isInstanceOf(SafeFetcher.FetchException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void honoursRobotsTxtWhenAsked() {
        SafeFetcher f = open();

        assertThat(f.get(base + "/public", "text/html", true).status()).isEqualTo(200);
        assertThatThrownBy(() -> f.get(base + "/private", "text/html", true)).isInstanceOf(SafeFetcher.FetchException.class)
                .hasMessageContaining("robots.txt");
        assertThat(f.get(base + "/private", "text/html", false).body()).isEqualTo("secret"); // API calls skip robots
    }

    @Test
    void refusesInternalAddressesInNormalMode() {
        SafeFetcher strict = new SafeFetcher(false, 1000, Duration.ofSeconds(5), Duration.ZERO);

        assertThatThrownBy(() -> strict.get(base + "/public", "text/plain", false)).isInstanceOf(SafeFetcher.FetchException.class);
        assertThatThrownBy(() -> strict.get("http://169.254.169.254/latest/meta-data/", "text/plain", false))
                .isInstanceOf(SafeFetcher.FetchException.class);
    }
}
