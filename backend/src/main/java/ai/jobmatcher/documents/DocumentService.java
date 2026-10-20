package ai.jobmatcher.documents;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import ai.jobmatcher.auth.UserRepository;
import ai.jobmatcher.common.FieldCrypto;
import ai.jobmatcher.common.InvalidInputException;
import ai.jobmatcher.common.NotFoundException;
import ai.jobmatcher.cv.CvService;
import ai.jobmatcher.jobs.JobOffer;
import ai.jobmatcher.jobs.JobOfferRepository;
import ai.jobmatcher.matching.MatchReport;
import ai.jobmatcher.matching.MatchService;
import ai.jobmatcher.profile.ProfileService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class DocumentService {

    public record View(UUID id, UUID jobId, String type, String language, int version, String source, String generator, String subject,
            String body, List<Fact> facts, List<List<String>> paragraphFacts, List<String> warnings, String note, String jobLanguage,
            Instant createdAt) {
    }

    public record Summary(UUID id, int version, String source, String generator, String language, Instant createdAt) {
    }

    public record Export(byte[] bytes, String contentType, String filename) {
    }

    private static final TypeReference<List<Fact>> FACTS = new TypeReference<>() { };
    private static final TypeReference<List<List<String>>> PARAGRAPHS = new TypeReference<>() { };

    private final GeneratedDocumentRepository docs;
    private final JobOfferRepository offers;
    private final CvService cvs;
    private final MatchService matching;
    private final ProfileService profiles;
    private final UserRepository users;
    private final EvidenceSelector selector;
    private final LetterComposer letters;
    private final LetterAiWriter aiWriter;
    private final EmailComposer emails;
    private final DocumentExporter exporter;
    private final Map<String, LetterLocale> locales;
    private final FieldCrypto crypto;
    private final JsonMapper json;

    DocumentService(GeneratedDocumentRepository docs, JobOfferRepository offers, CvService cvs, MatchService matching,
            ProfileService profiles, UserRepository users, EvidenceSelector selector, LetterComposer letters, LetterAiWriter aiWriter,
            EmailComposer emails, DocumentExporter exporter, List<LetterLocale> localeBeans, FieldCrypto crypto, JsonMapper json) {
        this.docs = docs;
        this.offers = offers;
        this.cvs = cvs;
        this.matching = matching;
        this.profiles = profiles;
        this.users = users;
        this.selector = selector;
        this.letters = letters;
        this.aiWriter = aiWriter;
        this.emails = emails;
        this.exporter = exporter;
        this.locales = localeBeans.stream().collect(Collectors.toMap(LetterLocale::code, l -> l));
        this.crypto = crypto;
        this.json = json;
    }

    public List<String> supportedLanguages() {
        return locales.keySet().stream().sorted().toList();
    }

    // ---- create ----

    @Transactional
    public View createLetter(UUID userId, UUID jobId, UUID cvId, LetterOptions requested) {
        Prepared p = prepare(userId, jobId, cvId, requested);
        LetterDraft draft = letters.compose(p.ctx, p.options, p.locale);
        String note = "template";
        if (p.options.useAi()) {
            LetterAiWriter.Outcome out = aiWriter.write(p.ctx, p.options, p.locale,
                    p.report.skills().stream().filter(s -> s.level().equals("REQUIRED")).map(MatchReport.SkillResult::skill).toList());
            if (out.draft().isPresent()) {
                draft = out.draft().get();
            }
            note = out.note();
        }
        GeneratedDocument d = save(userId, jobId, cvId, GeneratedDocument.Type.LETTER, p, null, null, draft.plainText(), draft.generator(),
                GeneratedDocument.Source.GENERATED, draft.paragraphs().stream().map(LetterDraft.Paragraph::factIds).toList());
        return view(d, List.of(), note);
    }

    @Transactional
    public View createEmail(UUID userId, UUID jobId, UUID cvId, LetterOptions requested, boolean withLetter, String contactName) {
        Prepared p = prepare(userId, jobId, cvId, requested);
        String name = contactName == null || contactName.isBlank() ? null : contactName.strip().replaceAll("[^\\p{L}\\p{M}' .-]", "");
        EmailComposer.Email email = emails.compose(p.ctx, p.options, p.locale, withLetter, name == null || name.isBlank() ? null : name);
        GeneratedDocument d = save(userId, jobId, cvId, GeneratedDocument.Type.EMAIL, p, name, email.subject(), email.body(), "template",
                GeneratedDocument.Source.GENERATED, List.of(email.factIds()));
        return view(d, List.of(), "template");
    }

    // ---- read / edit ----

    @Transactional(readOnly = true)
    public List<Summary> versions(UUID userId, UUID jobId, GeneratedDocument.Type type) {
        return docs.findByUserIdAndJobIdAndDocTypeOrderByVersionDesc(userId, jobId, type).stream()
                .map(d -> new Summary(d.getId(), d.getVersion(), d.getSource().name(), d.getGenerator(), d.getLanguage(), d.getCreatedAt())).toList();
    }

    /** Every letter and email version, for the data export. */
    @Transactional(readOnly = true)
    public List<View> exportAll(UUID userId) {
        return docs.findByUserIdOrderByCreatedAtAsc(userId).stream().map(d -> view(d, List.of(), null)).toList();
    }

    @Transactional(readOnly = true)
    public View get(UUID userId, UUID id) {
        GeneratedDocument d = owned(userId, id);
        List<String> warnings = d.getSource() == GeneratedDocument.Source.EDITED ? warningsFor(d, crypto.decrypt(d.getBody())) : List.of();
        return view(d, warnings, null);
    }

    /** Saves the user's own wording as a new version. It is checked against the CV and offer and warnings are returned, never blocks. */
    @Transactional
    public View edit(UUID userId, UUID id, String text, String subject) {
        GeneratedDocument base = owned(userId, id);
        if (text == null || text.isBlank() || text.length() > 20_000) {
            throw new InvalidInputException("The text must not be empty or longer than 20,000 characters.");
        }
        GeneratedDocument d = new GeneratedDocument();
        d.setUserId(userId);
        d.setJobId(base.getJobId());
        d.setCvId(base.getCvId());
        d.setDocType(base.getDocType());
        d.setLanguage(base.getLanguage());
        d.setVersion(docs.maxVersion(userId, base.getJobId(), base.getDocType()) + 1);
        d.setSource(GeneratedDocument.Source.EDITED);
        d.setGenerator(base.getGenerator());
        d.setParamsJson(base.getParamsJson());
        d.setSubject(base.getDocType() == GeneratedDocument.Type.EMAIL ? crypto.encrypt(cleanSubject(subject, base)) : null);
        d.setBody(crypto.encrypt(text.strip()));
        d.setFactsJson(base.getFactsJson());
        d.setParagraphFactsJson("[]");
        docs.save(d);
        return view(d, warningsFor(d, text), null);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        docs.delete(owned(userId, id));
    }

    @Transactional(readOnly = true)
    public Export export(UUID userId, UUID id, String format, String to) {
        GeneratedDocument d = owned(userId, id);
        String body = crypto.decrypt(d.getBody());
        String base = (d.getDocType() == GeneratedDocument.Type.LETTER ? "motivation-letter" : "application-email") + "-v" + d.getVersion();
        String f = format == null ? "txt" : format.toLowerCase();
        if (d.getDocType() == GeneratedDocument.Type.LETTER) {
            return switch (f) {
                case "pdf" -> new Export(exporter.pdf(body), "application/pdf", base + ".pdf");
                case "docx" -> new Export(exporter.docx(body), "application/vnd.openxmlformats-officedocument.wordprocessingml.document", base + ".docx");
                case "txt" -> new Export(exporter.txt(body), "text/plain; charset=UTF-8", base + ".txt");
                default -> throw new InvalidInputException("Letters export as pdf, docx or txt.");
            };
        }
        String subject = d.getSubject() == null ? "" : crypto.decrypt(d.getSubject());
        return switch (f) {
            case "eml" -> new Export(exporter.eml(to == null || to.isBlank() ? null : to.strip(), subject, body), "message/rfc822", base + ".eml");
            case "txt" -> new Export(exporter.txt("Subject: " + subject + "\n\n" + body), "text/plain; charset=UTF-8", base + ".txt");
            default -> throw new InvalidInputException("Emails export as eml or txt.");
        };
    }

    // ---- internals ----

    private record Prepared(EvidenceSelector.Context ctx, LetterOptions options, LetterLocale locale, MatchReport report) {
    }

    private Prepared prepare(UUID userId, UUID jobId, UUID cvId, LetterOptions requested) {
        JobOffer job = offers.findById(jobId).filter(o -> o.getOwnerUserId() == null || o.getOwnerUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Job not found."));
        CvService.Detail cv = cvs.get(userId, cvId);
        MatchReport report = matching.match(userId, cvId, jobId).report();
        var user = users.findById(userId).orElseThrow();

        // An unsupported language is an error only when the user asked for it; otherwise fall back to a supported one.
        String lang = requested.language() != null ? requested.language()
                : locales.containsKey(job.getLanguage()) ? job.getLanguage()
                : locales.containsKey(user.getPreferredLanguage()) ? user.getPreferredLanguage() : "en";
        LetterLocale locale = locales.get(lang);
        if (locale == null) {
            throw new InvalidInputException("Supported languages: " + String.join(", ", supportedLanguages()) + ".");
        }
        EvidenceSelector.Context ctx = selector.select(cv.parsed(), report, profiles.get(userId), job.getTitle(), job.getCompany(),
                job.getDescription(), cv.text(), user.getDisplayName());
        if (ctx.facts().isEmpty()) {
            throw new InvalidInputException(
                    "Your CV shows nothing that relates to this offer, so there is nothing true to write about. Review your CV data or choose another offer.");
        }
        LetterOptions o = new LetterOptions(lang, requested.tone(), requested.length(), requested.formality(), requested.useAi());
        return new Prepared(ctx, o, locale, report);
    }

    private GeneratedDocument save(UUID userId, UUID jobId, UUID cvId, GeneratedDocument.Type type, Prepared p, String contactName,
            String subject, String body, String generator, GeneratedDocument.Source source, List<List<String>> paragraphFacts) {
        GeneratedDocument d = new GeneratedDocument();
        d.setUserId(userId);
        d.setJobId(jobId);
        d.setCvId(cvId);
        d.setDocType(type);
        d.setLanguage(p.options.language());
        d.setVersion(docs.maxVersion(userId, jobId, type) + 1);
        d.setSource(source);
        d.setGenerator(generator);
        Map<String, Object> params = new java.util.LinkedHashMap<>(Map.of("tone", p.options.tone(), "length", p.options.length(),
                "formality", p.options.formality(), "useAi", p.options.useAi()));
        if (contactName != null) {
            params.put("contactName", contactName);
        }
        d.setParamsJson(json.writeValueAsString(params));
        d.setSubject(subject == null ? null : crypto.encrypt(subject));
        d.setBody(crypto.encrypt(body));
        d.setFactsJson(crypto.encrypt(json.writeValueAsString(p.ctx.facts())));
        d.setParagraphFactsJson(json.writeValueAsString(paragraphFacts));
        return docs.save(d);
    }

    private GeneratedDocument owned(UUID userId, UUID id) {
        return docs.findByIdAndUserId(id, userId).orElseThrow(() -> new NotFoundException("Document not found."));
    }

    private View view(GeneratedDocument d, List<String> warnings, String note) {
        return new View(d.getId(), d.getJobId(), d.getDocType().name(), d.getLanguage(), d.getVersion(), d.getSource().name(), d.getGenerator(),
                d.getSubject() == null ? null : crypto.decrypt(d.getSubject()), crypto.decrypt(d.getBody()),
                json.readValue(crypto.decrypt(d.getFactsJson()), FACTS), json.readValue(d.getParagraphFactsJson(), PARAGRAPHS), warnings, note,
                offers.findById(d.getJobId()).map(JobOffer::getLanguage).orElse(null), d.getCreatedAt());
    }

    private String cleanSubject(String subject, GeneratedDocument base) {
        String s = subject == null || subject.isBlank() ? crypto.decrypt(base.getSubject()) : subject;
        return s.replaceAll("[\\r\\n]+", " ").strip();
    }

    /** Numbers and names in the text that appear nowhere in the CV or the offer. */
    private List<String> warningsFor(GeneratedDocument d, String text) {
        StringBuilder corpus = new StringBuilder();
        offers.findById(d.getJobId()).ifPresent(j -> corpus.append(j.getTitle()).append('\n').append(j.getCompany()).append('\n').append(j.getDescription()));
        if (d.getCvId() != null) {
            try {
                CvService.Detail cv = cvs.get(d.getUserId(), d.getCvId());
                corpus.append('\n').append(cv.text());
            } catch (NotFoundException e) {
                // The CV was deleted; the stored facts below still count.
            }
        }
        json.readValue(crypto.decrypt(d.getFactsJson()), FACTS).forEach(f -> corpus.append('\n').append(f.text()));
        users.findById(d.getUserId()).ifPresent(u -> corpus.append('\n').append(u.getDisplayName()));
        // A recipient name the user took from a published contact is theirs to use.
        corpus.append('\n').append(json.readTree(d.getParamsJson()).path("contactName").asString(""));
        return GroundingValidator.unsupported(text, corpus.toString().toLowerCase());
    }
}
