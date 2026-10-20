package ai.jobmatcher.cv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.time.Duration;

import ai.jobmatcher.common.InvalidInputException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class TextExtractorTest {

    private final TextExtractor extractor = new TextExtractor(50_000, Duration.ofSeconds(20));

    private static final String LINE = "Jane Doe is a backend engineer who builds reliable Java services and APIs.";

    @Test
    void extractsTextFromDocx() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText(LINE);
            doc.write(out);
        }
        byte[] bytes = out.toByteArray();

        assertThat(new UploadValidator(1_000_000).validate(bytes, "cv.docx")).isEqualTo(UploadValidator.Kind.DOCX);
        assertThat(extractor.extract(bytes)).contains("backend engineer");
    }

    @Test
    void extractsTextFromPdf() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(LINE);
                cs.endText();
            }
            doc.save(out);
        }
        byte[] bytes = out.toByteArray();

        assertThat(new UploadValidator(1_000_000).validate(bytes, "cv.pdf")).isEqualTo(UploadValidator.Kind.PDF);
        assertThat(extractor.extract(bytes)).contains("backend engineer");
    }

    @Test
    void explainsWhenNoTextCanBeExtracted() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(out);
        }

        assertThatThrownBy(() -> extractor.extract(out.toByteArray())).isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("OCR is not enabled");
    }

    @Test
    void capsExtractedLength() throws Exception {
        TextExtractor small = new TextExtractor(200, Duration.ofSeconds(20));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            for (int i = 0; i < 100; i++) {
                doc.createParagraph().createRun().setText(LINE);
            }
            doc.write(out);
        }

        assertThat(small.extract(out.toByteArray()).length()).isLessThanOrEqualTo(220);
    }
}
