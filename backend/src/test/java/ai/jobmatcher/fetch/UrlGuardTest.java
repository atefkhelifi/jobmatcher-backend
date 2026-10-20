package ai.jobmatcher.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.junit.jupiter.api.Test;

class UrlGuardTest {

    private final UrlGuard guard = new UrlGuard(false);

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/",
            "http://localhost/admin",
            "http://169.254.169.254/latest/meta-data/",
            "http://10.0.0.5/",
            "http://192.168.1.1/",
            "http://172.16.0.9/",
            "http://172.31.255.255/",
            "http://100.64.0.1/",
            "http://0.0.0.0/",
            "http://[::1]/",
            "http://[fc00::1]/",
            "http://[fe80::1]/",
            "http://[::ffff:127.0.0.1]/",
            "http://[::ffff:10.1.2.3]/",
            "http://2130706433/",
            "http://foo.localhost/",
            "http://printer.local/",
            "http://db.internal/"
    })
    void blocksPrivateAndInternalTargets(String url) {
        assertThatThrownBy(() -> guard.check(url)).isInstanceOf(UrlGuard.Blocked.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "file:///etc/passwd",
            "ftp://example.com/",
            "gopher://example.com/",
            "javascript:alert(1)",
            "http://user:pass@93.184.216.34/",
            "http://93.184.216.34:8080/",
            "http://93.184.216.34:22/",
            "not a url",
            "http:///nohost"
    })
    void blocksUnsafeSchemesPortsCredentialsAndGarbage(String url) {
        assertThatThrownBy(() -> guard.check(url)).isInstanceOf(UrlGuard.Blocked.class);
    }

    @ParameterizedTest
    @ValueSource(strings = { "https://93.184.216.34/jobs", "http://1.1.1.1/", "https://[2606:4700:4700::1111]/" })
    void allowsPublicAddressLiterals(String url) {
        assertThat(guard.check(url)).isNotNull();
    }

    @Test
    void allowPrivateModeIsOnlyForTests() {
        assertThat(new UrlGuard(true).check("http://127.0.0.1:8080/x")).isNotNull();
    }
}
