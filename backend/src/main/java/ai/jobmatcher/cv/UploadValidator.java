package ai.jobmatcher.cv;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import ai.jobmatcher.common.InvalidInputException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Rejects anything that is not a plain PDF or DOCX before it reaches a parser. The type is decided from the
 * file's magic bytes, never from the client-supplied name or Content-Type.
 */
@Component
public class UploadValidator {

    public enum Kind { PDF, DOCX }

    static final long MAX_UNCOMPRESSED_BYTES = 50L * 1024 * 1024;
    static final int MAX_ZIP_ENTRIES = 2_000;

    private final long maxBytes;

    public UploadValidator(@Value("${app.upload.max-bytes}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public Kind validate(byte[] content, String filename) {
        if (content == null || content.length == 0) {
            throw new InvalidInputException("The uploaded file is empty.");
        }
        if (content.length > maxBytes) {
            throw new InvalidInputException("The file is larger than " + (maxBytes / (1024 * 1024)) + " MB.");
        }
        String ext = extension(filename);
        if (isPdf(content)) {
            requireExtension(ext, "pdf");
            return Kind.PDF;
        }
        if (isZip(content)) {
            requireExtension(ext, "docx");
            inspectDocx(content);
            return Kind.DOCX;
        }
        throw new InvalidInputException("Only PDF and DOCX files are accepted.");
    }

    private static boolean isPdf(byte[] c) {
        // The header may be preceded by a few bytes of junk, but must appear within the first 1 KB.
        int limit = Math.min(c.length - 4, 1024);
        for (int i = 0; i <= limit; i++) {
            if (c[i] == '%' && c[i + 1] == 'P' && c[i + 2] == 'D' && c[i + 3] == 'F') {
                return true;
            }
        }
        return false;
    }

    private static boolean isZip(byte[] c) {
        return c.length > 4 && c[0] == 'P' && c[1] == 'K' && c[2] == 3 && c[3] == 4;
    }

    private static void inspectDocx(byte[] content) {
        boolean hasContentTypes = false;
        boolean hasDocument = false;
        int entries = 0;
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zip.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) {
                    throw new InvalidInputException("The document has too many internal parts.");
                }
                String name = e.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith("/") || name.contains("..")) {
                    throw new InvalidInputException("The document contains an invalid internal path.");
                }
                if (name.endsWith("vbaproject.bin") || name.contains("/activex/") || name.endsWith(".exe")) {
                    throw new InvalidInputException("Documents with macros or embedded executables are not accepted.");
                }
                hasContentTypes |= name.equals("[content_types].xml");
                hasDocument |= name.equals("word/document.xml");
                int n;
                while ((n = zip.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_UNCOMPRESSED_BYTES) {
                        throw new InvalidInputException("The document expands to an unreasonable size.");
                    }
                }
            }
        } catch (IOException | IllegalArgumentException ex) {
            throw new InvalidInputException("The DOCX file is corrupt or unreadable.");
        }
        if (!hasContentTypes || !hasDocument) {
            throw new InvalidInputException("This ZIP archive is not a Word (DOCX) document.");
        }
    }

    private static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static void requireExtension(String actual, String expected) {
        if (!actual.equals(expected)) {
            throw new InvalidInputException("The file extension does not match its content (expected ." + expected + ").");
        }
    }
}
