package ai.jobmatcher.cv;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import ai.jobmatcher.common.FieldCrypto;
import ai.jobmatcher.common.InvalidInputException;
import ai.jobmatcher.common.NotFoundException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CvService {

    static final int MAX_CVS_PER_USER = 20;

    public record Summary(UUID id, String label, String sourceType, String originalFilename, String language,
            boolean reviewed, Instant createdAt, Instant updatedAt) {
    }

    public record Detail(Summary summary, ParsedCv parsed, String text) {
    }

    public record AnalysisView(UUID id, Instant createdAt, int overallScore, CvAnalyzer.Report report,
            AiReviewer.Review aiReview) {
    }

    /** What is persisted in cv_analysis.report_json. */
    record StoredReport(CvAnalyzer.Report report, AiReviewer.Review aiReview) {
    }

    private final CvDocumentRepository docs;
    private final CvAnalysisRepository analyses;
    private final UploadValidator validator;
    private final TextExtractor extractor;
    private final CvParser parser;
    private final CvAnalyzer analyzer;
    private final AiReviewer aiReviewer;
    private final FieldCrypto crypto;
    private final JsonMapper json;

    CvService(CvDocumentRepository docs, CvAnalysisRepository analyses, UploadValidator validator, TextExtractor extractor,
            CvParser parser, CvAnalyzer analyzer, AiReviewer aiReviewer, FieldCrypto crypto, JsonMapper json) {
        this.docs = docs;
        this.analyses = analyses;
        this.validator = validator;
        this.extractor = extractor;
        this.parser = parser;
        this.analyzer = analyzer;
        this.aiReviewer = aiReviewer;
        this.crypto = crypto;
        this.json = json;
    }

    @Transactional
    public Detail upload(UUID userId, MultipartFile file, String label) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (java.io.IOException e) {
            throw new InvalidInputException("The file could not be read.");
        }
        UploadValidator.Kind kind = validator.validate(content, file.getOriginalFilename());
        String text = extractor.extract(content);
        String filename = sanitizeFilename(file.getOriginalFilename());
        return create(userId, label == null || label.isBlank() ? stripExtension(filename) : label, kind.name(), filename,
                kind == UploadValidator.Kind.PDF ? "application/pdf"
                        : "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                (long) content.length, text);
    }

    @Transactional
    public Detail createFromText(UUID userId, String label, String text) {
        String clean = text.strip();
        if (clean.length() < 50) {
            throw new InvalidInputException("The CV text is too short to analyse (minimum 50 characters).");
        }
        return create(userId, label, "TEXT", null, "text/plain", (long) clean.length(), clean);
    }

    private Detail create(UUID userId, String label, String sourceType, String filename, String mediaType, Long size,
            String text) {
        if (docs.countByUserId(userId) >= MAX_CVS_PER_USER) {
            throw new InvalidInputException("You can keep at most " + MAX_CVS_PER_USER + " CV versions. Delete one first.");
        }
        ParsedCv parsed = parser.parse(text);
        CvDocument d = docs.save(new CvDocument(userId, label.strip(), sourceType, filename, mediaType, size,
                CvParser.detectLanguage(text), crypto.encrypt(text), crypto.encrypt(json.writeValueAsString(parsed))));
        return detail(d);
    }

    @Transactional(readOnly = true)
    public List<Summary> list(UUID userId) {
        return docs.findByUserIdOrderByCreatedAtDesc(userId).stream().map(CvService::summary).toList();
    }

    @Transactional(readOnly = true)
    public Detail get(UUID userId, UUID id) {
        return detail(owned(userId, id));
    }

    @Transactional
    public Detail saveReviewed(UUID userId, UUID id, ParsedCv parsed, String label) {
        CvDocument d = owned(userId, id);
        d.setParsedJson(crypto.encrypt(json.writeValueAsString(normalize(parsed))));
        d.setReviewed(true);
        if (label != null && !label.isBlank()) {
            d.setLabel(label.strip());
        }
        return detail(docs.save(d));
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        if (docs.deleteByIdAndUserId(id, userId) == 0) {
            throw new NotFoundException("CV not found.");
        }
    }

    @Transactional
    public AnalysisView analyze(UUID userId, UUID id, boolean withAi) {
        CvDocument d = owned(userId, id);
        String text = crypto.decrypt(d.getExtractedText());
        ParsedCv parsed = readParsed(d);
        CvAnalyzer.Report report = analyzer.analyze(text, parsed, d.getLanguage());
        AiReviewer.Review ai = withAi ? aiReviewer.review(text) : new AiReviewer.Review("skipped", "none", List.of());
        CvAnalysisEntity saved = analyses.save(new CvAnalysisEntity(id, userId, report.overallScore(),
                crypto.encrypt(json.writeValueAsString(new StoredReport(report, ai)))));
        return new AnalysisView(saved.getId(), saved.getCreatedAt(), report.overallScore(), report, ai);
    }

    @Transactional(readOnly = true)
    public Optional<AnalysisView> latestAnalysis(UUID userId, UUID id) {
        owned(userId, id);
        return analyses.findFirstByCvIdAndUserIdOrderByCreatedAtDesc(id, userId).map(a -> {
            StoredReport s = json.readValue(crypto.decrypt(a.getReportJson()), StoredReport.class);
            return new AnalysisView(a.getId(), a.getCreatedAt(), a.getOverallScore(), s.report(), s.aiReview());
        });
    }

    /** The user's corrected profile as Markdown, ready to paste into a CV template. Contains only what the user approved. */
    @Transactional(readOnly = true)
    public String exportMarkdown(UUID userId, UUID id) {
        return CvMarkdown.render(readParsed(owned(userId, id)));
    }

    // ---- helpers ----

    private CvDocument owned(UUID userId, UUID id) {
        return docs.findByIdAndUserId(id, userId).orElseThrow(() -> new NotFoundException("CV not found."));
    }

    private Detail detail(CvDocument d) {
        return new Detail(summary(d), readParsed(d), crypto.decrypt(d.getExtractedText()));
    }

    private ParsedCv readParsed(CvDocument d) {
        return json.readValue(crypto.decrypt(d.getParsedJson()), ParsedCv.class);
    }

    private static Summary summary(CvDocument d) {
        return new Summary(d.getId(), d.getLabel(), d.getSourceType(), d.getOriginalFilename(), d.getLanguage(),
                d.isReviewed(), d.getCreatedAt(), d.getUpdatedAt());
    }

    /** Null collections from the client become empty ones so downstream code never has to check. */
    private static ParsedCv normalize(ParsedCv p) {
        return new ParsedCv(p.fullName(), p.email(), p.phone(), p.summary(), nz(p.skills()), nz(p.experience()),
                nz(p.education()), nz(p.languages()), nz(p.certifications()), nz(p.projects()), p.yearsOfExperience());
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }

    static String sanitizeFilename(String name) {
        if (name == null) {
            return "cv";
        }
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[^\\p{L}\\p{N} ._\\-()]", "_");
        return base.length() > 120 ? base.substring(base.length() - 120) : base;
    }

    private static String stripExtension(String f) {
        int dot = f.lastIndexOf('.');
        return dot > 0 ? f.substring(0, dot) : f;
    }
}
