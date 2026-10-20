package ai.jobmatcher.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import ai.jobmatcher.common.InvalidInputException;
import ai.jobmatcher.cv.TextExtractor;
import ai.jobmatcher.cv.UploadValidator;

import org.junit.jupiter.api.Test;

class DocumentExporterTest {

    private final DocumentExporter exporter = new DocumentExporter();
    private final TextExtractor reader = new TextExtractor(100_000, Duration.ofSeconds(20));

    private static final String LETTER = "Madame, Monsieur,\n\nJ'ai occupé le poste de développeur chez Northwind & Co. <Équipe> “citation” – 40 %.\n\n"
            + "Cordialement,\nJane Doe";

    @Test
    void docxIsAValidWordDocumentThatOpensAndKeepsAccentsAndSpecialCharacters() throws Exception {
        byte[] bytes = exporter.docx(LETTER);

        assertThat(new UploadValidator(1_000_000).validate(bytes, "letter.docx")).isEqualTo(UploadValidator.Kind.DOCX);
        String text = reader.extract(bytes);
        assertThat(text).contains("J'ai occupé le poste de développeur chez Northwind & Co. <Équipe>").contains("Cordialement,").contains("Jane Doe");
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                assertThat(e.getName()).doesNotContain("vba");
            }
        }
    }

    @Test
    void docxStripsCharactersThatAreIllegalInXml() throws Exception {
        assertThat(reader.extract(exporter.docx("Hello\u0000 wor\u0008ld! This sentence is long enough to be read back as text."))).contains("Hello world!");
    }

    @Test
    void pdfOpensAndKeepsFrenchAccents() {
        byte[] bytes = exporter.pdf(LETTER);

        assertThat(new UploadValidator(1_000_000).validate(bytes, "letter.pdf")).isEqualTo(UploadValidator.Kind.PDF);
        String text = reader.extract(bytes);
        assertThat(text).contains("occupé le poste de développeur").contains("Cordialement,").contains("Jane Doe");
    }

    @Test
    void pdfWrapsLongTextAcrossPages() {
        String longText = ("Une ligne de texte assez longue pour forcer le retour à la ligne automatique dans le document. ").repeat(120);

        byte[] bytes = exporter.pdf(longText);

        String text = reader.extract(bytes);
        assertThat(text).contains("retour à la ligne automatique");
        assertThat(text.length()).isGreaterThan(5000);
    }

    @Test
    void emlIsAnUnsentDraftWithEncodedSubjectAndBody() {
        byte[] bytes = exporter.eml("careers@acme-corp.io", "Candidature : Développeur Java\r\nBcc: attacker@evil.io", "Bonjour,\n\nÀ bientôt.");
        String eml = new String(bytes, StandardCharsets.US_ASCII);

        assertThat(eml).startsWith("X-Unsent: 1\r\n").contains("To: careers@acme-corp.io\r\n").contains("Content-Type: text/plain; charset=UTF-8");
        assertThat(eml).doesNotContain("Bcc:");
        String subjectB64 = eml.lines().filter(l -> l.startsWith("Subject: =?UTF-8?B?")).findFirst().orElseThrow().replaceAll("^Subject: =\\?UTF-8\\?B\\?|\\?=$", "");
        assertThat(new String(Base64.getDecoder().decode(subjectB64), StandardCharsets.UTF_8)).isEqualTo("Candidature : Développeur Java Bcc: attacker@evil.io");
        String body = eml.substring(eml.indexOf("\r\n\r\n") + 4).replace("\r\n", "");
        assertThat(new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8)).isEqualTo("Bonjour,\r\n\r\nÀ bientôt.");
    }

    @Test
    void emlRecipientIsOptionalButMustBeAPlainAddress() {
        assertThat(new String(exporter.eml(null, "S", "B"), StandardCharsets.US_ASCII)).doesNotContain("To:");
        assertThatThrownBy(() -> exporter.eml("a@b.io\r\nBcc: x@y.io", "S", "B")).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> exporter.eml("not an address", "S", "B")).isInstanceOf(InvalidInputException.class);
    }
}
