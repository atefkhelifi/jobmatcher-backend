package ai.jobmatcher.jobs;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads schema.org {@code JobPosting} structured data (JSON-LD) from a page. Employers publish it so search engines can
 * list their openings, which makes it a legitimate machine-readable source. Only fields present in the markup are
 * used; nothing is inferred from the visible page.
 */
@Component
public class JobPostingParser {

    private final JsonMapper json;

    public JobPostingParser(JsonMapper json) {
        this.json = json;
    }

    public Optional<RawJob> parse(String html, URI pageUrl) {
        for (Element script : Jsoup.parse(html).select("script[type=application/ld+json]")) {
            try {
                Optional<JsonNode> posting = findPosting(json.readTree(script.data()));
                if (posting.isPresent()) {
                    return Optional.ofNullable(map(posting.get(), pageUrl));
                }
            } catch (RuntimeException e) {
                // Malformed JSON-LD block: try the next one.
            }
        }
        return Optional.empty();
    }

    private Optional<JsonNode> findPosting(JsonNode node) {
        if (node.isArray()) {
            for (JsonNode n : node) {
                Optional<JsonNode> r = findPosting(n);
                if (r.isPresent()) {
                    return r;
                }
            }
            return Optional.empty();
        }
        if (!node.isObject()) {
            return Optional.empty();
        }
        JsonNode type = node.path("@type");
        boolean isPosting = type.isArray()
                ? java.util.stream.StreamSupport.stream(type.spliterator(), false).anyMatch(t -> "JobPosting".equals(t.asString("")))
                : "JobPosting".equals(type.asString(""));
        if (isPosting) {
            return Optional.of(node);
        }
        return findPosting(node.path("@graph"));
    }

    private RawJob map(JsonNode p, URI pageUrl) {
        String title = text(p.path("title"));
        if (title == null) {
            return null;
        }
        String description = HtmlText.toText(p.path("description").asString(""));
        if (description.isBlank()) {
            return null;
        }
        JsonNode org = p.path("hiringOrganization");
        String company = org.isString() ? text(org) : text(org.path("name"));
        JsonNode loc = p.path("jobLocation").isArray() ? p.path("jobLocation").path(0) : p.path("jobLocation");
        JsonNode addr = loc.path("address");
        String city = text(addr.path("addressLocality"));
        String region = text(addr.path("addressRegion"));
        String country = addr.path("addressCountry").isObject() ? text(addr.path("addressCountry").path("name")) : text(addr.path("addressCountry"));
        String location = city != null ? (region != null ? city + ", " + region : city) : region;
        boolean telecommute = "TELECOMMUTE".equalsIgnoreCase(p.path("jobLocationType").asString(""));

        JsonNode salary = p.path("baseSalary");
        JsonNode value = salary.path("value");
        Integer min = integer(value.path("minValue"));
        Integer max = integer(value.path("maxValue"));
        if (min == null && max == null && value.isNumber()) {
            min = integer(value);
            max = min;
        }
        String unit = value.path("unitText").asString("");
        boolean yearly = unit.equalsIgnoreCase("YEAR");
        String currency = salary.path("currency").asString(null);
        // Salary is only kept when stated per year, so unlike units are never compared.
        if (!yearly || currency == null || !currency.matches("[A-Za-z]{3}")) {
            min = null;
            max = null;
            currency = null;
        } else {
            currency = currency.toUpperCase(Locale.ROOT);
        }
        if (min != null && max != null && min > max) {
            min = null;
            max = null;
            currency = null;
        }

        String apply = text(p.path("url"));
        String canonical = pageUrl.toString();
        String id = text(p.path("identifier").isObject() ? p.path("identifier").path("value") : p.path("identifier"));
        return new RawJob(id != null ? id : sha1(canonical), title, company, location,
                country != null && country.length() == 2 ? country.toUpperCase(Locale.ROOT) : null,
                telecommute ? RemoteMode.REMOTE : RemoteMode.UNKNOWN, description, canonical,
                apply != null && apply.startsWith("http") ? apply : canonical, employmentType(p.path("employmentType")), min, max,
                currency, instant(text(p.path("datePosted"))), instant(text(p.path("validThrough"))));
    }

    private static String employmentType(JsonNode n) {
        JsonNode first = n.isArray() ? n.path(0) : n;
        String s = first.asString(null);
        return s == null || s.isBlank() ? null : s.toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode n) {
        if (n == null || !(n.isString() || n.isNumber())) {
            return null;
        }
        String s = n.asString("").trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer integer(JsonNode n) {
        return n.isNumber() ? (int) Math.round(n.asDouble()) : null;
    }

    static Instant instant(String s) {
        if (s == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (RuntimeException e) {
            try {
                return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s).atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }

    private static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
