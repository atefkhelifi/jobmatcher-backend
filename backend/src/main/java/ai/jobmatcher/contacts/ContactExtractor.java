package ai.jobmatcher.contacts;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Finds contact details that someone has explicitly published: email addresses and "contact: Name, title" lines in an
 * offer, and email addresses, careers pages and application links on an employer's own pages. It reads what is written
 * and never constructs an address from a name or a company domain.
 */
@Component
public class ContactExtractor {

    public enum Kind { EMAIL, PERSON, URL }

    public record Candidate(Kind kind, String value, String name, String title) {
    }

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern EMAIL = Pattern.compile("(?<![\\w.+%-])([A-Za-z0-9._%+-]{1,64})@([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,24})(?![\\w-])");
    private static final Set<String> NOT_TLDS = Set.of("png", "jpg", "jpeg", "gif", "svg", "webp", "css", "js", "ico", "pdf");
    private static final Pattern NO_REPLY = Pattern.compile("^(?:no-?reply|do-?not-?reply|mailer-daemon|postmaster|abuse|webmaster|privacy|dpo)$", FLAGS);
    private static final Pattern HR_LOCAL = Pattern.compile(
            "^(?:jobs?|careers?|recruit\\w*|hr|rh|talent\\w*|apply|application\\w*|candidatures?|recrutement|bewerbung\\w*|karriere|personal|human-?resources|people|stellen)$", FLAGS);
    private static final Pattern HR_CONTEXT = Pattern.compile(
            "recruit|career|carri[eè]re|candidat|postuler|apply|application|hiring|talent|human resources|ressources humaines|\\bhr\\b|\\brh\\b|bewerb|karriere|personalabteilung|jobs?\\b",
            FLAGS);
    private static final Pattern PERSON = Pattern.compile(
            "(?i:contact(?:\\s+person)?|recruiter|recruteu(?:r|se)|talent acquisition|ansprechpartner(?:in)?|your contact|votre contact|interlocuteu?r(?:trice)?|personne [àa] contacter)"
                    + "\\s*[:\\-–]\\s*(\\p{Lu}[\\p{L}'’-]+(?:\\s+\\p{Lu}[\\p{L}'’-]+){1,2})(?:\\s*[,(–-]\\s*([^\\n,.()]{3,60}))?");
    private static final Set<String> NOT_NAMES = Set.of("human", "resources", "team", "hr", "department", "recruiting", "recruitment", "talent",
            "acquisition", "ressources", "humaines", "equipe", "équipe", "service", "our", "the", "for", "more", "information");
    private static final Pattern LINK_TEXT = Pattern.compile("careers?|carri[eè]res?|jobs?|recrut|karriere|stellen|join us|nous rejoindre|work with us|contact", FLAGS);
    private static final Pattern APPLY_TEXT = Pattern.compile("apply|postuler|candidater|bewerben|application form|formulaire de candidature", FLAGS);
    private static final Set<String> SECOND_LEVEL = Set.of("co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "co.nz", "com.br", "co.jp",
            "co.in", "com.mx", "com.tr", "co.za", "com.sg", "com.hk", "com.cn");

    /** An offer's own text: any published address counts, because the offer is the context. */
    public List<Candidate> fromAdText(String text) {
        Map<String, Candidate> out = new LinkedHashMap<>();
        Matcher m = EMAIL.matcher(text);
        while (m.find()) {
            addEmail(out, m.group(1), m.group(2));
        }
        Matcher p = PERSON.matcher(text);
        while (p.find()) {
            String name = p.group(1).strip();
            boolean plausible = java.util.Arrays.stream(name.split("\\s+")).noneMatch(t -> NOT_NAMES.contains(t.toLowerCase(Locale.ROOT)));
            if (plausible) {
                String title = p.group(2) == null ? null : p.group(2).strip();
                out.putIfAbsent("P:" + name.toLowerCase(Locale.ROOT), new Candidate(Kind.PERSON, name, name, title));
            }
        }
        return List.copyOf(out.values());
    }

    /** What an employer's page publishes: mailto links, addresses near recruiting wording, and links to careers and application pages. */
    public Page fromHtml(String html, URI pageUrl) {
        Document doc = Jsoup.parse(html, pageUrl.toString());
        Map<String, Candidate> emails = new LinkedHashMap<>();
        List<URI> follow = new ArrayList<>();
        Map<String, Candidate> links = new LinkedHashMap<>();

        for (Element a : doc.select("a[href]")) {
            String href = a.attr("href").strip();
            if (href.toLowerCase(Locale.ROOT).startsWith("mailto:")) {
                String addr = href.substring(7).split("[?,]")[0];
                Matcher m = EMAIL.matcher(addr);
                if (m.matches() || m.find()) {
                    String local = m.group(1);
                    // Only the link's own wording and its immediate block count, never the whole page.
                    Element block = a.parent();
                    String context = a.text() + " " + (block == null || block.tagName().equals("body") || block.tagName().equals("html") || block.text().length() > 300 ? "" : block.ownText());
                    if (HR_LOCAL.matcher(local).matches() || HR_CONTEXT.matcher(context).find()) {
                        addEmail(emails, local, m.group(2));
                    }
                }
                continue;
            }
            URI target = resolve(pageUrl, a.absUrl("href"));
            if (target == null || !sameSite(pageUrl, target)) {
                continue;
            }
            String label = a.text() + " " + target.getPath();
            if (APPLY_TEXT.matcher(a.text()).find()) {
                links.putIfAbsent(target.toString(), new Candidate(Kind.URL, target.toString(), null, "application"));
            } else if (LINK_TEXT.matcher(label).find() && follow.size() < 8) {
                follow.add(target);
            }
        }

        // Addresses written out in the text, judged by the wording of the same paragraph, list item or cell only.
        for (Element el : doc.select("p, li, td, th, address, span, div, dd")) {
            String own = el.ownText();
            if (own.length() > 400) {
                continue;
            }
            Matcher m = EMAIL.matcher(own);
            while (m.find()) {
                if (HR_LOCAL.matcher(m.group(1)).matches() || HR_CONTEXT.matcher(own).find()) {
                    addEmail(emails, m.group(1), m.group(2));
                }
            }
        }
        List<Candidate> all = new ArrayList<>(emails.values());
        all.addAll(links.values());
        return new Page(List.copyOf(all), List.copyOf(follow));
    }

    public record Page(List<Candidate> contacts, List<URI> followUp) {
    }

    // ---- domains ----

    public static String registrableDomain(String host) {
        String h = host.toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
        String[] parts = h.split("\\.");
        if (parts.length <= 2) {
            return h;
        }
        String last2 = parts[parts.length - 2] + "." + parts[parts.length - 1];
        return SECOND_LEVEL.contains(last2) ? parts[parts.length - 3] + "." + last2 : last2;
    }

    public static boolean sameSite(URI a, URI b) {
        return a.getHost() != null && b.getHost() != null && registrableDomain(a.getHost()).equals(registrableDomain(b.getHost()));
    }

    // ---- helpers ----

    private static void addEmail(Map<String, Candidate> out, String local, String domain) {
        String tld = domain.substring(domain.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (NOT_TLDS.contains(tld) || NO_REPLY.matcher(local).matches() || domain.toLowerCase(Locale.ROOT).endsWith("example.com")) {
            return;
        }
        String address = local + "@" + domain;
        out.putIfAbsent("E:" + address.toLowerCase(Locale.ROOT), new Candidate(Kind.EMAIL, address, null, null));
    }

    private static URI resolve(URI base, String abs) {
        try {
            URI u = URI.create(abs);
            return u.getScheme() != null && (u.getScheme().equals("http") || u.getScheme().equals("https")) && u.getHost() != null ? u : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
