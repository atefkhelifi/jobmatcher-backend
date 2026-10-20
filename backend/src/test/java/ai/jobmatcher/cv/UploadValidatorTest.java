package ai.jobmatcher.cv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import ai.jobmatcher.common.InvalidInputException;

import org.junit.jupiter.api.Test;

class UploadValidatorTest {

    private final UploadValidator validator = new UploadValidator(1024 * 1024);

    static byte[] zip(String... names) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (String n : names) {
                z.putNextEntry(new ZipEntry(n));
                z.write("<x/>".getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    void acceptsPdfByMagicBytes() {
        byte[] pdf = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);

        assertThat(validator.validate(pdf, "cv.pdf")).isEqualTo(UploadValidator.Kind.PDF);
    }

    @Test
    void acceptsMinimalDocx() throws IOException {
        byte[] docx = zip("[Content_Types].xml", "word/document.xml");

        assertThat(validator.validate(docx, "cv.docx")).isEqualTo(UploadValidator.Kind.DOCX);
    }

    @Test
    void rejectsExecutableRenamedToPdf() {
        byte[] exe = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0};

        assertThatThrownBy(() -> validator.validate(exe, "cv.pdf")).isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("Only PDF and DOCX");
    }

    @Test
    void rejectsContentThatDoesNotMatchTheExtension() {
        byte[] pdf = "%PDF-1.4 content".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> validator.validate(pdf, "cv.docx")).isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("extension");
    }

    @Test
    void rejectsPlainZipThatIsNotWord() throws IOException {
        assertThatThrownBy(() -> validator.validate(zip("a.txt"), "cv.docx")).isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("not a Word");
    }

    @Test
    void rejectsDocumentsWithMacros() throws IOException {
        byte[] macro = zip("[Content_Types].xml", "word/document.xml", "word/vbaProject.bin");

        assertThatThrownBy(() -> validator.validate(macro, "cv.docx")).isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("macros");
    }

    @Test
    void rejectsPathTraversalEntries() throws IOException {
        byte[] evil = zip("[Content_Types].xml", "word/document.xml", "../../evil.txt");

        assertThatThrownBy(() -> validator.validate(evil, "cv.docx")).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void rejectsOversizeAndEmptyFiles() {
        assertThatThrownBy(() -> validator.validate(new byte[2 * 1024 * 1024], "cv.pdf")).isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("larger than");
        assertThatThrownBy(() -> validator.validate(new byte[0], "cv.pdf")).isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejectsTooManyZipEntries() throws IOException {
        String[] names = new String[UploadValidator.MAX_ZIP_ENTRIES + 5];
        for (int i = 0; i < names.length; i++) {
            names[i] = "f" + i + ".xml";
        }

        assertThatThrownBy(() -> validator.validate(zip(names), "cv.docx")).isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("too many");
    }
}
