package ai.jobmatcher.common;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Sliding-window limiter keyed by caller. In-memory by design: the MVP runs a single API instance.
 * Replace with a shared store before scaling horizontally.
 *
 * <p>Idle keys are evicted so that an attacker cannot grow the map without bound by varying the key (for example many
 * addresses or many email names on the login endpoint).
 */
@Component
public class RateLimiter {

    static final int PURGE_THRESHOLD = 5_000;
    static final Duration MAX_IDLE = Duration.ofHours(2);

    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public RateLimiter() {
        this(Clock.systemUTC());
    }

    RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Records a hit, or throws when {@code limit} hits already happened within {@code window}. */
    public void check(String key, int limit, Duration window) {
        long now = clock.millis();
        if (hits.size() > PURGE_THRESHOLD) {
            purge(now);
        }
        long windowMs = window.toMillis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= windowMs) {
                q.pollFirst();
            }
            if (q.size() >= limit) {
                long retry = Math.max(1, (windowMs - (now - q.peekFirst()) + 999) / 1000);
                throw new RateLimitExceededException(retry);
            }
            q.addLast(now);
        }
    }

    /** Number of tracked keys, for tests and monitoring. */
    int size() {
        return hits.size();
    }

    private void purge(long now) {
        long idleMs = MAX_IDLE.toMillis();
        hits.entrySet().removeIf(e -> {
            Deque<Long> q = e.getValue();
            synchronized (q) {
                return q.isEmpty() || now - q.peekLast() > idleMs;
            }
        });
    }
}
