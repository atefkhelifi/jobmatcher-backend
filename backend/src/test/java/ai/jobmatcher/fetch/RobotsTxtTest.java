package ai.jobmatcher.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RobotsTxtTest {

    private static final String ROBOTS = """
            # comment
            User-agent: *
            Disallow: /private
            Disallow: /search?
            Allow: /private/public
            Disallow: /*.pdf$

            User-agent: JobMatcherBot
            Disallow: /bot-only
            """;

    @Test
    void usesTheGroupNamingTheBotInsteadOfTheWildcard() {
        RobotsTxt r = RobotsTxt.parse(ROBOTS, "JobMatcherBot");

        assertThat(r.allows("/bot-only/x")).isFalse();
        assertThat(r.allows("/private/a")).isTrue();
    }

    @Test
    void fallsBackToWildcardGroup() {
        RobotsTxt r = RobotsTxt.parse(ROBOTS, "OtherBot");

        assertThat(r.allows("/jobs/1")).isTrue();
        assertThat(r.allows("/private/a")).isFalse();
        assertThat(r.allows("/private/public/x")).isTrue(); // longest match wins
        assertThat(r.allows("/search?q=java")).isFalse();
        assertThat(r.allows("/files/cv.pdf")).isFalse();
        assertThat(r.allows("/files/cv.pdf.html")).isTrue();
    }

    @Test
    void emptyDisallowMeansAllowEverything() {
        assertThat(RobotsTxt.parse("User-agent: *\nDisallow:\n", "X").allows("/anything")).isTrue();
    }

    @Test
    void helpersForMissingOrBrokenFiles() {
        assertThat(RobotsTxt.allowAll().allows("/x")).isTrue();
        assertThat(RobotsTxt.disallowAll().allows("/x")).isFalse();
    }
}
