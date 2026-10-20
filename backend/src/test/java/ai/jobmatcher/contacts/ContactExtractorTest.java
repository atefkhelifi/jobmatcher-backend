package ai.jobmatcher.contacts;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

class ContactExtractorTest {

    private final ContactExtractor x = new ContactExtractor();

    private static List<String> values(List<ContactExtractor.Candidate> l, ContactExtractor.Kind k) {
        return l.stream().filter(c -> c.kind() == k).map(ContactExtractor.Candidate::value).toList();
    }

    @Test
    void readsAddressesAndNamedContactsPublishedInTheOffer() {
        String ad = """
                Send your CV to careers@acme-corp.io or to Jobs@Acme-Corp.io.
                Contact: Marie Dupont, Talent Acquisition Manager
                Please do not reply to no-reply@acme-corp.io. Logo: logo@2x.png
                """;

        List<ContactExtractor.Candidate> found = x.fromAdText(ad);

        assertThat(values(found, ContactExtractor.Kind.EMAIL)).containsExactly("careers@acme-corp.io", "Jobs@Acme-Corp.io");
        ContactExtractor.Candidate person = found.stream().filter(c -> c.kind() == ContactExtractor.Kind.PERSON).findFirst().orElseThrow();
        assertThat(person.name()).isEqualTo("Marie Dupont");
        assertThat(person.title()).isEqualTo("Talent Acquisition Manager");
    }

    @Test
    void neverBuildsAnAddressFromANameOrACompany() {
        String ad = "Contact: Marie Dupont, Recruiter at Acme Corp (acme-corp.io). Apply through our website.";

        List<ContactExtractor.Candidate> found = x.fromAdText(ad);

        assertThat(values(found, ContactExtractor.Kind.EMAIL)).isEmpty();
        assertThat(values(found, ContactExtractor.Kind.PERSON)).containsExactly("Marie Dupont");
    }

    @Test
    void generalLabelsAreNotPeople() {
        assertThat(x.fromAdText("Contact: Human Resources\nContact: Our Team for more information")).isEmpty();
        assertThat(x.fromAdText("No contact details here, only a great job.")).isEmpty();
    }

    @Test
    void employerPageGivesRecruitingAddressesAndSameSiteLinksOnly() {
        String html = """
                <html><body>
                  <a href="mailto:careers@initech-hq.io?subject=Hi">Careers team</a>
                  <a href="mailto:press@initech-hq.io">Press</a>
                  <p>Questions about hiring? Write to recruiting@initech-hq.io.</p>
                  <p>Sales: sales@initech-hq.io</p>
                  <a href="/jobs/apply/42">Apply now</a>
                  <a href="https://other-site.example.org/apply">Apply now</a>
                  <a href="/company/careers">Our careers</a>
                  <a href="/blog">Blog</a>
                </body></html>""";

        ContactExtractor.Page page = x.fromHtml(html, URI.create("https://www.initech-hq.io/jobs/42"));

        assertThat(values(page.contacts(), ContactExtractor.Kind.EMAIL)).containsExactlyInAnyOrder("careers@initech-hq.io", "recruiting@initech-hq.io");
        assertThat(values(page.contacts(), ContactExtractor.Kind.URL)).containsExactly("https://www.initech-hq.io/jobs/apply/42");
        assertThat(page.followUp()).extracting(URI::getPath).containsExactly("/company/careers");
    }

    @Test
    void registrableDomainHandlesCommonSuffixes() {
        assertThat(ContactExtractor.registrableDomain("www.initech-hq.io")).isEqualTo("initech-hq.io");
        assertThat(ContactExtractor.registrableDomain("careers.acme.co.uk")).isEqualTo("acme.co.uk");
        assertThat(ContactExtractor.registrableDomain("boards.greenhouse.io")).isEqualTo("greenhouse.io");
        assertThat(ContactExtractor.sameSite(URI.create("https://a.acme.fr/x"), URI.create("https://jobs.acme.fr/y"))).isTrue();
        assertThat(ContactExtractor.sameSite(URI.create("https://acme.fr"), URI.create("https://acme.com"))).isFalse();
    }

    @Test
    void jobBoardsAreNeverTreatedAsTheEmployer() {
        assertThat(ContactDiscoveryService.isBoard(URI.create("https://www.arbeitnow.com/jobs/companies/x/y"))).isTrue();
        assertThat(ContactDiscoveryService.isBoard(URI.create("https://boards.greenhouse.io/acme/jobs/1"))).isTrue();
        assertThat(ContactDiscoveryService.isBoard(URI.create("https://careers.acme.fr/jobs/1"))).isFalse();
    }
}
