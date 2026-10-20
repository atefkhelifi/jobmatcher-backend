package ai.jobmatcher.contacts;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import ai.jobmatcher.common.NotFoundException;
import ai.jobmatcher.fetch.SafeFetcher;
import ai.jobmatcher.jobs.JobOffer;
import ai.jobmatcher.jobs.JobOfferRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Looks for publicly published recruiting contacts for an offer: in the offer text itself, and, when the offer lives on an
 * employer's own site, on that site's careers and contact pages. Job boards and applicant-tracking hosts are never treated
 * as the employer, so their own addresses are not reported as the employer's.
 */
@Service
public class ContactDiscoveryService {

    static final int MAX_PAGES = 4;
    static final int MAX_FOLLOW = 2;

    /** Hosts that publish other companies' offers. Contacts found on them belong to the host, not the employer. */
    static final Set<String> BOARD_DOMAINS = Set.of("arbeitnow.com", "arbeitnow.co.uk", "francetravail.fr", "pole-emploi.fr", "greenhouse.io",
            "lever.co", "ashbyhq.com", "linkedin.com", "indeed.com", "welcometothejungle.com", "glassdoor.com", "monster.com", "stepstone.de",
            "jooble.org", "adzuna.com", "adzuna.fr", "workable.com", "smartrecruiters.com", "personio.de", "personio.com", "recruitee.com",
            "teamtailor.com", "join.com", "breezy.hr", "jobvite.com", "icims.com", "myworkdayjobs.com");

    public record ContactView(UUID id, String kind, String value, String name, String title, String sourceUrl, String sourceKind,
            boolean verified, Instant discoveredAt) {
    }

    public record ContactsView(List<ContactView> contacts, String applyUrl, String jobUrl, boolean searched, Instant searchedAt, int pagesRead,
            String note, boolean hasEmail) {
    }

    private record Found(ContactExtractor.Candidate c, String sourceUrl, String sourceKind, boolean verified) {
    }

    private final JobOfferRepository offers;
    private final HrContactRepository contacts;
    private final ContactSearchRepository searches;
    private final ContactExtractor extractor;
    private final SafeFetcher fetcher;
    private final TransactionTemplate tx;

    ContactDiscoveryService(JobOfferRepository offers, HrContactRepository contacts, ContactSearchRepository searches, ContactExtractor extractor,
            SafeFetcher fetcher, TransactionTemplate tx) {
        this.offers = offers;
        this.contacts = contacts;
        this.searches = searches;
        this.extractor = extractor;
        this.fetcher = fetcher;
        this.tx = tx;
    }

    public ContactsView discover(UUID userId, UUID jobId) {
        JobOffer job = visible(userId, jobId);
        List<Found> found = new ArrayList<>();

        for (ContactExtractor.Candidate c : extractor.fromAdText(job.getTitle() + "\n" + job.getDescription())) {
            found.add(new Found(c, job.getJobUrl(), "AD_TEXT", true));
        }

        int pages = 0;
        String note = null;
        URI start = parse(job.getJobUrl());
        if (start != null && isBoard(start)) {
            note = "board";
        } else if (start != null) {
            Deque<URI> queue = new ArrayDeque<>(List.of(start));
            URI apply = parse(job.getApplyUrl());
            if (apply != null && !apply.equals(start) && ContactExtractor.sameSite(start, apply)) {
                queue.add(apply);
            }
            Set<String> seen = new HashSet<>();
            int followed = 0;
            while (!queue.isEmpty() && pages < MAX_PAGES) {
                URI next = queue.poll();
                if (!seen.add(next.toString())) {
                    continue;
                }
                try {
                    SafeFetcher.Response r = fetcher.get(next.toString(), "text/html,application/xhtml+xml", true);
                    if (r.status() != 200) {
                        continue;
                    }
                    pages++;
                    ContactExtractor.Page page = extractor.fromHtml(r.body(), r.finalUri());
                    for (ContactExtractor.Candidate c : page.contacts()) {
                        boolean official = c.kind() != ContactExtractor.Kind.EMAIL || emailOnSite(c.value(), r.finalUri());
                        found.add(new Found(c, r.finalUri().toString(), "OFFICIAL_PAGE", official));
                    }
                    for (URI f : page.followUp()) {
                        if (followed < MAX_FOLLOW && !seen.contains(f.toString()) && ContactExtractor.sameSite(start, f)) {
                            queue.add(f);
                            followed++;
                        }
                    }
                } catch (SafeFetcher.FetchException e) {
                    note = e.getMessage();
                }
            }
        }

        final int pagesRead = pages;
        final String finalNote = note;
        tx.executeWithoutResult(s -> {
            for (Found f : found) {
                String kind = f.c().kind().name();
                if (!contacts.existsByJobIdAndKindAndValue(jobId, kind, f.c().value())) {
                    HrContact h = new HrContact();
                    h.setJobId(jobId);
                    h.setKind(kind);
                    h.setValue(cut(f.c().value(), 500));
                    h.setName(cut(f.c().name(), 200));
                    h.setTitle(cut(f.c().title(), 200));
                    h.setSourceUrl(cut(f.sourceUrl(), 2000));
                    h.setSourceKind(f.sourceKind());
                    h.setVerified(f.verified());
                    contacts.save(h);
                }
            }
            ContactSearch cs = searches.findById(new ContactSearch.Key(jobId, userId)).orElseGet(() -> new ContactSearch(jobId, userId));
            cs.setSearchedAt(Instant.now());
            cs.setPagesRead(pagesRead);
            cs.setNote(cut(finalNote, 300));
            searches.save(cs);
        });
        return view(userId, jobId);
    }

    @Transactional(readOnly = true)
    public ContactsView view(UUID userId, UUID jobId) {
        JobOffer job = visible(userId, jobId);
        List<ContactView> list = contacts.findByJobIdOrderByVerifiedDescDiscoveredAtAsc(jobId).stream()
                .map(h -> new ContactView(h.getId(), h.getKind(), h.getValue(), h.getName(), h.getTitle(), h.getSourceUrl(), h.getSourceKind(),
                        h.isVerified(), h.getDiscoveredAt())).toList();
        ContactSearch s = searches.findById(new ContactSearch.Key(jobId, userId)).orElse(null);
        return new ContactsView(list, job.getApplyUrl(), job.getJobUrl(), s != null, s == null ? null : s.getSearchedAt(),
                s == null ? 0 : s.getPagesRead(), s == null ? null : s.getNote(), list.stream().anyMatch(c -> c.kind().equals("EMAIL")));
    }

    private JobOffer visible(UUID userId, UUID jobId) {
        return offers.findById(jobId).filter(o -> o.getOwnerUserId() == null || o.getOwnerUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Job not found."));
    }

    static boolean isBoard(URI u) {
        return u.getHost() != null && BOARD_DOMAINS.contains(ContactExtractor.registrableDomain(u.getHost()));
    }

    private static boolean emailOnSite(String email, URI page) {
        String domain = email.substring(email.indexOf('@') + 1).toLowerCase(Locale.ROOT);
        return page.getHost() != null && ContactExtractor.registrableDomain(domain).equals(ContactExtractor.registrableDomain(page.getHost()));
    }

    private static URI parse(String s) {
        try {
            return s == null ? null : URI.create(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
