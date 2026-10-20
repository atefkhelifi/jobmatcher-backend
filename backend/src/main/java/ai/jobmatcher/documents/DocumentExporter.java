package ai.jobmatcher.documents;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import ai.jobmatcher.common.InvalidInputException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

/** Turns plain document text into downloadable files. Nothing is sent anywhere. */
@Component
public class DocumentExporter {

    private static final Pattern ADDRESS = Pattern.compile("^[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9.\\-]{1,190}\\.[A-Za-z]{2,}$");
    private static final Pattern XML_ILLEGAL = Pattern.compile("[^\\u0009\\u000A\\u000D\\u0020-\\uD7FF\\uE000-\\uFFFD]");

    public byte[] txt(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** A minimal, valid WordprocessingML package: one paragraph per line, no styles or macros. */
    public byte[] docx(String text) {
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\\R", -1)) {
            body.append("<w:p><w:r><w:t xml:space=\"preserve\">").append(xml(line)).append("</w:t></w:r></w:p>");
        }
        String document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" + body
                + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>";
        String types = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>";
        String rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            put(z, "[Content_Types].xml", types);
            put(z, "_rels/.rels", rels);
            put(z, "word/document.xml", document);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    /** A4 PDF in Helvetica with word wrapping and page breaks. Characters outside Latin-1 become "?". */
    public byte[] pdf(String text) {
        final float margin = 64;
        final float size = 11;
        final float leading = 16;
        PDRectangle box = PDRectangle.A4;
        float width = box.getWidth() - 2 * margin;
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        try (PDDocument doc = new PDDocument()) {
            List<String> lines = wrap(text, font, size, width);
            int perPage = (int) ((box.getHeight() - 2 * margin) / leading);
            for (int start = 0; start == 0 || start < lines.size(); start += perPage) {
                PDPage page = new PDPage(box);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, size);
                    cs.newLineAtOffset(margin, box.getHeight() - margin);
                    for (int i = start; i < Math.min(lines.size(), start + perPage); i++) {
                        cs.showText(lines.get(i));
                        cs.newLineAtOffset(0, -leading);
                    }
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("PDF export failed", e);
        }
    }

    /**
     * An unsent draft message ({@code .eml}) that mail programs open for editing. The recipient is optional and must be a
     * plain address; subject and body are encoded so line breaks cannot inject headers.
     */
    public byte[] eml(String to, String subject, String body) {
        if (to != null && !ADDRESS.matcher(to).matches()) {
            throw new InvalidInputException("The recipient is not a valid email address.");
        }
        String cleanSubject = subject == null ? "" : subject.replaceAll("[\\r\\n]+", " ").strip();
        StringBuilder sb = new StringBuilder();
        sb.append("X-Unsent: 1\r\n").append("MIME-Version: 1.0\r\n");
        if (to != null) {
            sb.append("To: ").append(to).append("\r\n");
        }
        sb.append("Subject: =?UTF-8?B?").append(Base64.getEncoder().encodeToString(cleanSubject.getBytes(StandardCharsets.UTF_8))).append("?=\r\n");
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n").append("Content-Transfer-Encoding: base64\r\n\r\n");
        String encoded = Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(body.replace("\r\n", "\n").replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
        sb.append(encoded).append("\r\n");
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    // ---- helpers ----

    private static List<String> wrap(String text, PDType1Font font, float size, float width) throws IOException {
        List<String> out = new ArrayList<>();
        for (String paragraph : text.split("\\R", -1)) {
            String clean = latin1(paragraph);
            if (clean.isBlank()) {
                out.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : clean.split(" ")) {
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (font.getStringWidth(candidate) / 1000 * size > width && line.length() > 0) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            out.add(line.toString());
        }
        return out;
    }

    /** Standard PDF fonts cover Latin-1 (all French and English accents); anything else is replaced. */
    private static String latin1(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"').replace('–', '-')
                .replace('—', '-').replace('•', '-').replace(' ', ' ').replace("\t", "  ").toCharArray()) {
            sb.append(c >= 0x20 && c <= 0xFF && !(c >= 0x7F && c <= 0x9F) ? c : '?');
        }
        return sb.toString();
    }

    private static String xml(String s) {
        return XML_ILLEGAL.matcher(s).replaceAll("").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void put(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }
}
