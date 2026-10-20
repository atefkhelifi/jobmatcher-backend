package ai.jobmatcher.fetch;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SSRF guard. A URL is allowed only when it is http(s) on a standard port, carries no credentials, and every
 * address its host resolves to is public. Applied again on every redirect hop.
 */
public final class UrlGuard {

    public static final class Blocked extends RuntimeException {
        public Blocked(String message) {
            super(message);
        }
    }

    private static final Set<Integer> PORTS = Set.of(80, 443, -1);

    private final boolean allowPrivate;

    public UrlGuard(boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    /** Returns the validated URI, or throws {@link Blocked} with a user-safe reason. */
    public URI check(String raw) {
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new Blocked("The URL is not valid.");
        }
        return check(uri);
    }

    public URI check(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new Blocked("Only http and https URLs are allowed.");
        }
        if (uri.getUserInfo() != null) {
            throw new Blocked("URLs with embedded credentials are not allowed.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new Blocked("The URL has no host.");
        }
        if (!allowPrivate && !PORTS.contains(uri.getPort())) {
            throw new Blocked("Only standard web ports are allowed.");
        }
        if (!allowPrivate) {
            String h = host.toLowerCase(Locale.ROOT);
            if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) {
                throw new Blocked("Local addresses are not allowed.");
            }
            for (InetAddress a : resolve(host)) {
                if (isPrivate(a)) {
                    throw new Blocked("Addresses on private or internal networks are not allowed.");
                }
            }
        }
        return uri;
    }

    private static List<InetAddress> resolve(String host) {
        try {
            return List.of(InetAddress.getAllByName(host));
        } catch (UnknownHostException e) {
            throw new Blocked("The host could not be resolved.");
        }
    }

    static boolean isPrivate(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            return b0 == 0 // "this" network
                    || (b0 == 100 && b1 >= 64 && b1 <= 127) // carrier-grade NAT
                    || (b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0) // IETF protocol assignments
                    || (b0 == 198 && (b1 == 18 || b1 == 19)) // benchmarking
                    || b0 >= 240; // reserved and broadcast
        }
        if (a instanceof Inet6Address) {
            int b0 = b[0] & 0xff;
            if ((b0 & 0xfe) == 0xfc) { // unique local fc00::/7
                return true;
            }
            // IPv4-mapped (::ffff:a.b.c.d): judge the embedded IPv4 address.
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                mapped &= b[i] == 0;
            }
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return isPrivate(InetAddress.getByAddress(new byte[] { b[12], b[13], b[14], b[15] }));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
        }
        return false;
    }
}
