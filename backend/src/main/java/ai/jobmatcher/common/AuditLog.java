package ai.jobmatcher.common;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Security-relevant events (sign-in, sign-up, export, deletion) on a dedicated logger, so they can be routed and kept apart
 * from application logs. Entries hold an internal user id and a truncated network address, never an email, a name, CV text
 * or a password.
 */
@Component
public class AuditLog {

    private static final Logger audit = LoggerFactory.getLogger("audit");

    public void event(String name, UUID userId, String clientAddress) {
        audit.info("event={} user={} client={}", name, userId == null ? "-" : userId, mask(clientAddress));
    }

    /** IPv4 loses its last octet and IPv6 everything after the first 48 bits: enough to spot abuse, not to identify a person. */
    static String mask(String address) {
        if (address == null || address.isBlank()) {
            return "-";
        }
        if (address.contains(".")) {
            int dot = address.lastIndexOf('.');
            return address.substring(0, dot) + ".0";
        }
        String[] parts = address.split(":");
        return parts.length >= 3 ? parts[0] + ":" + parts[1] + ":" + parts[2] + "::" : "-";
    }
}
