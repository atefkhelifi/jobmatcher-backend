package ai.jobmatcher.cv;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import ai.jobmatcher.common.InvalidInputException;
import jakarta.annotation.PreDestroy;

import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Apache Tika text extraction with a hard character cap and a wall-clock timeout so a hostile or huge document
 * cannot tie up the server. Only text is kept; the original file is never stored.
 */
@Component
public class TextExtractor {

    private static final Logger log = LoggerFactory.getLogger(TextExtractor.class);
    private static final int MIN_USEFUL_CHARS = 50;

    private final int maxChars;
    private final Duration timeout;
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "tika-extract");
        t.setDaemon(true);
        return t;
    });

    public TextExtractor(@Value("${app.upload.max-text-chars}") int maxChars,
            @Value("${app.upload.parse-timeout}") Duration timeout) {
        this.maxChars = maxChars;
        this.timeout = timeout;
    }

    public String extract(byte[] content) {
        Future<String> job = pool.submit(() -> {
            BodyContentHandler handler = new BodyContentHandler(maxChars);
            try {
                new AutoDetectParser().parse(TikaInputStream.get(new ByteArrayInputStream(content)), handler, new Metadata(), new ParseContext());
            } catch (org.xml.sax.SAXException e) {
                if (!handler.toString().isBlank() && e.getClass().getSimpleName().contains("WriteLimit")) {
                    log.debug("Text truncated at {} characters", maxChars);
                } else {
                    throw e;
                }
            }
            return handler.toString();
        });
        String text;
        try {
            text = job.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            job.cancel(true);
            throw new InvalidInputException("Reading the document took too long and was stopped.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InvalidInputException("Reading the document was interrupted.");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof EncryptedDocumentException) {
                throw new InvalidInputException("Password-protected documents cannot be read. Remove the password and retry.");
            }
            log.warn("Document extraction failed: {}", e.getCause() == null ? "unknown" : e.getCause().getClass().getSimpleName());
            throw new InvalidInputException("The document could not be read.");
        }
        String cleaned = text.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
        if (cleaned.length() < MIN_USEFUL_CHARS) {
            throw new InvalidInputException(
                    "No selectable text was found. If this is a scanned document, OCR is not enabled in this installation; paste the CV text instead.");
        }
        return cleaned;
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
