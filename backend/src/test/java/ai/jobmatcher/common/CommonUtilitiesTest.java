package ai.jobmatcher.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class CommonUtilitiesTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void cryptoRoundTripsAndUsesRandomIv() {
        FieldCrypto c = new FieldCrypto(KEY);

        String a = c.encrypt("Jane Doe, 12 rue Exemple");
        String b = c.encrypt("Jane Doe, 12 rue Exemple");

        assertThat(a).isNotEqualTo(b).doesNotContain("Jane");
        assertThat(c.decrypt(a)).isEqualTo("Jane Doe, 12 rue Exemple");
    }

    @Test
    void cryptoDetectsTampering() {
        FieldCrypto c = new FieldCrypto(KEY);
        byte[] raw = Base64.getDecoder().decode(c.encrypt("secret"));
        raw[raw.length - 1] ^= 1;

        assertThatThrownBy(() -> c.decrypt(Base64.getEncoder().encodeToString(raw))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cryptoRejectsBadKeys() {
        assertThatThrownBy(() -> new FieldCrypto("")).isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new FieldCrypto("not base64!!")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rateLimiterForgetsIdleKeysSoTheMapCannotGrowWithoutBound() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId z) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        RateLimiter limiter = new RateLimiter(clock);

        for (int i = 0; i < RateLimiter.PURGE_THRESHOLD + 50; i++) {
            limiter.check("ip-" + i, 5, Duration.ofMinutes(1));
        }
        assertThat(limiter.size()).isGreaterThan(RateLimiter.PURGE_THRESHOLD);

        now.set(now.get().plus(RateLimiter.MAX_IDLE).plusSeconds(60));
        limiter.check("fresh", 5, Duration.ofMinutes(1));

        assertThat(limiter.size()).isEqualTo(1);
    }

    @Test
    void auditEntriesMaskTheClientAddress() {
        assertThat(AuditLog.mask("203.0.113.57")).isEqualTo("203.0.113.0");
        assertThat(AuditLog.mask("2001:db8:85a3:8d3:1319:8a2e:370:7348")).isEqualTo("2001:db8:85a3::");
        assertThat(AuditLog.mask(null)).isEqualTo("-");
        assertThat(AuditLog.mask("")).isEqualTo("-");
    }

    @Test
    void rateLimiterBlocksAfterLimitAndRecoversAfterWindow() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId z) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        RateLimiter limiter = new RateLimiter(clock);

        limiter.check("k", 2, Duration.ofMinutes(1));
        limiter.check("k", 2, Duration.ofMinutes(1));
        assertThatThrownBy(() -> limiter.check("k", 2, Duration.ofMinutes(1)))
                .isInstanceOfSatisfying(RateLimitExceededException.class, e -> assertThat(e.retryAfterSeconds()).isBetween(1L, 60L));
        limiter.check("other", 2, Duration.ofMinutes(1));

        now.set(now.get().plusSeconds(61));
        limiter.check("k", 2, Duration.ofMinutes(1));
    }
}
