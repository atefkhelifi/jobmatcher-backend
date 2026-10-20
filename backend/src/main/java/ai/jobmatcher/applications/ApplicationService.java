package ai.jobmatcher.applications;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import ai.jobmatcher.auth.UserRepository;
import ai.jobmatcher.common.ConflictException;
import ai.jobmatcher.common.ConfirmationRequiredException;
import ai.jobmatcher.common.FieldCrypto;
import ai.jobmatcher.common.InvalidInputException;
import ai.jobmatcher.common.NotFoundException;
import ai.jobmatcher.cv.CvDocumentRepository;
import ai.jobmatcher.documents.GeneratedDocument;
import ai.jobmatcher.documents.GeneratedDocumentRepository;
import ai.jobmatcher.jobs.Fingerprint;
import ai.jobmatcher.jobs.JobOffer;
import ai.jobmatcher.jobs.JobOfferRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationService {

    static final int MAX_APPLICATIONS = 500;
    static final int MAX_ITEMS_PER_APPLICATION = 100;

    // ---- views ----

    public record Summary(UUID id, UUID jobId, String title, String company, String location, String jobUrl, ApplicationStatus status,
            Instant appliedAt, Instant deadlineAt, Instant statusChangedAt, Instant createdAt, Instant nextInterviewAt, Instant nextReminderAt,
            int openReminders, int overdueReminders, List<ApplicationStatus> allowedNext) {
    }

    public record Duplicate(UUID id, String title, String company, ApplicationStatus status, Instant createdAt, boolean sameOffer) {
    }

    public record Event(UUID id, String type, ApplicationStatus from, ApplicationStatus to, String note, Instant occurredAt) {
    }

    public record Contact(UUID id, String name, String role, String email, String phone, String note) {
    }

    public record Interview(UUID id, String kind, Instant scheduledAt, int durationMinutes, String location, String notes) {
    }

    public record Reminder(UUID id, UUID applicationId, String applicationTitle, Instant dueAt, String text, Instant doneAt, boolean overdue) {
    }

    public record Attachment(UUID id, String kind, UUID documentId, String label, String url) {
    }

    public record Detail(Summary summary, UUID cvId, String notes, List<ApplicationStatus> allowedNext, List<Event> timeline, List<Contact> contacts,
            List<Interview> interviews, List<Reminder> reminders, List<Attachment> attachments) {
    }

    public record Dashboard(Map<ApplicationStatus, Long> byStatus, long total, List<Reminder> dueReminders, List<UpcomingInterview> upcomingInterviews) {
    }

    public record UpcomingInterview(UUID applicationId, String applicationTitle, String company, String kind, Instant scheduledAt, String location) {
    }

    // ---- requests ----

    public record Create(UUID jobId, String title, String company, String location, String jobUrl, ApplicationStatus status, UUID cvId,
            boolean confirmDuplicate) {
    }

    public record Update(String title, String company, String location, String jobUrl, String notes, Instant appliedAt, Instant deadlineAt, UUID cvId) {
    }

    private final ApplicationRepository apps;
    private final ApplicationEventRepository events;
    private final ApplicationContactRepository contacts;
    private final ApplicationInterviewRepository interviews;
    private final ApplicationReminderRepository reminders;
    private final ApplicationAttachmentRepository attachments;
    private final JobOfferRepository offers;
    private final CvDocumentRepository cvs;
    private final GeneratedDocumentRepository documents;
    private final UserRepository users;
    private final FieldCrypto crypto;

    ApplicationService(ApplicationRepository apps, ApplicationEventRepository events, ApplicationContactRepository contacts,
            ApplicationInterviewRepository interviews, ApplicationReminderRepository reminders, ApplicationAttachmentRepository attachments,
            JobOfferRepository offers, CvDocumentRepository cvs, GeneratedDocumentRepository documents, UserRepository users, FieldCrypto crypto) {
        this.apps = apps;
        this.events = events;
        this.contacts = contacts;
        this.interviews = interviews;
        this.reminders = reminders;
        this.attachments = attachments;
        this.offers = offers;
        this.cvs = cvs;
        this.documents = documents;
        this.users = users;
        this.crypto = crypto;
    }

    // ---- create / list ----

    /** Existing applications for the same offer, or for the same role (title, company, city) posted elsewhere. */
    @Transactional(readOnly = true)
    public List<Duplicate> duplicatesFor(UUID userId, UUID jobId, String title, String company, String location) {
        String fp;
        UUID jid = jobId;
        if (jobId != null) {
            fp = visibleJob(userId, jobId).getFingerprint();
        } else {
            fp = Fingerprint.of(title, company, location);
        }
        List<JobApplication> found = jid != null ? apps.findPossibleDuplicates(userId, jid, fp) : apps.findByFingerprint(userId, fp);
        return found.stream().map(a -> new Duplicate(a.getId(), a.getTitle(), a.getCompany(), a.getStatus(), a.getCreatedAt(),
                jid != null && jid.equals(a.getJobId()))).toList();
    }

    @Transactional
    public Detail create(UUID userId, Create r) {
        if (apps.countByUserId(userId) >= MAX_APPLICATIONS) {
            throw new InvalidInputException("You can track at most " + MAX_APPLICATIONS + " applications. Delete finished ones first.");
        }
        ApplicationStatus status = r.status() == null ? ApplicationStatus.TO_REVIEW : r.status();
        if (!status.canStartHere()) {
            throw new InvalidInputException("A new application can start as discovered, to review, shortlisted, preparing or applied.");
        }
        JobApplication a = new JobApplication();
        a.setUserId(userId);
        if (r.jobId() != null) {
            JobOffer job = visibleJob(userId, r.jobId());
            a.setJobId(job.getId());
            a.setTitle(job.getTitle());
            a.setCompany(job.getCompany());
            a.setLocation(job.getLocationText());
            a.setCountry(job.getCountry());
            a.setJobUrl(job.getApplyUrl() != null ? job.getApplyUrl() : job.getJobUrl());
            a.setDeadlineAt(job.getValidThrough());
            a.setFingerprint(job.getFingerprint());
        } else {
            if (r.title() == null || r.title().isBlank()) {
                throw new InvalidInputException("Give the job title, or choose an offer.");
            }
            a.setTitle(clip(r.title().strip(), 300));
            a.setCompany(clip(blankToNull(r.company()), 300));
            a.setLocation(clip(blankToNull(r.location()), 300));
            a.setJobUrl(httpOrNull(r.jobUrl()));
            a.setFingerprint(Fingerprint.of(a.getTitle(), a.getCompany(), a.getLocation()));
        }
        if (!r.confirmDuplicate()) {
            List<Duplicate> dups = duplicatesFor(userId, a.getJobId(), a.getTitle(), a.getCompany(), a.getLocation());
            if (!dups.isEmpty()) {
                throw new ConfirmationRequiredException("duplicate-application",
                        "You already track this role. Confirm to add it again.", dups);
            }
        }
        if (r.cvId() != null) {
            // The browser remembers the CV it compared with; one deleted since then must not block saving the offer.
            cvs.findByIdAndUserId(r.cvId(), userId).ifPresent(cv -> a.setCvId(cv.getId()));
        }
        Instant now = Instant.now();
        a.setStatus(status);
        a.setStatusChangedAt(now);
        if (status == ApplicationStatus.APPLIED) {
            a.setAppliedAt(now);
        }
        apps.save(a);
        addEvent(a, ApplicationEvent.Type.CREATED, null, status, null, now);
        return detail(a);
    }

    @Transactional(readOnly = true)
    public List<Summary> list(UUID userId, ApplicationStatus status) {
        List<JobApplication> list = apps.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .filter(a -> status == null || a.getStatus() == status).toList();
        if (list.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = list.stream().map(JobApplication::getId).toList();
        Instant now = Instant.now();
        Map<UUID, Instant> nextInterview = new HashMap<>();
        for (ApplicationInterview i : interviews.findByApplicationIdInOrderByScheduledAtAsc(ids)) {
            if (i.getScheduledAt().isAfter(now)) {
                nextInterview.putIfAbsent(i.getApplicationId(), i.getScheduledAt());
            }
        }
        Map<UUID, List<ApplicationReminder>> open = reminders.findByApplicationIdInAndDoneAtIsNullOrderByDueAtAsc(ids).stream()
                .collect(Collectors.groupingBy(ApplicationReminder::getApplicationId));
        return list.stream().map(a -> summary(a, nextInterview.get(a.getId()), open.getOrDefault(a.getId(), List.of()))).toList();
    }

    /** Every application with its full history, for the data export. */
    @Transactional(readOnly = true)
    public List<Detail> exportAll(UUID userId) {
        return apps.findByUserIdOrderByUpdatedAtDesc(userId).stream().map(this::detail).toList();
    }

    @Transactional(readOnly = true)
    public Detail get(UUID userId, UUID id) {
        return detail(owned(userId, id));
    }

    @Transactional
    public Detail update(UUID userId, UUID id, Update r) {
        JobApplication a = owned(userId, id);
        if (r.title() == null || r.title().isBlank()) {
            throw new InvalidInputException("The job title must not be empty.");
        }
        a.setTitle(clip(r.title().strip(), 300));
        a.setCompany(clip(blankToNull(r.company()), 300));
        a.setLocation(clip(blankToNull(r.location()), 300));
        a.setJobUrl(httpOrNull(r.jobUrl()));
        a.setNotes(enc(r.notes()));
        a.setAppliedAt(r.appliedAt());
        a.setDeadlineAt(r.deadlineAt());
        a.setCvId(r.cvId() == null ? null : ownedCv(userId, r.cvId()));
        a.setFingerprint(Fingerprint.of(a.getTitle(), a.getCompany(), a.getLocation()));
        return detail(apps.save(a));
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        apps.delete(owned(userId, id));
    }

    // ---- status ----

    @Transactional
    public Detail moveTo(UUID userId, UUID id, ApplicationStatus target, String note, Integer followUpDays) {
        JobApplication a = owned(userId, id);
        ApplicationStatus from = a.getStatus();
        if (!from.canMoveTo(target)) {
            throw new ConflictException("An application cannot move from " + from + " to " + target + ".");
        }
        if (followUpDays != null && (followUpDays < 1 || followUpDays > 90)) {
            throw new InvalidInputException("The follow-up delay must be between 1 and 90 days.");
        }
        Instant now = Instant.now();
        a.setStatus(target);
        a.setStatusChangedAt(now);
        if (target == ApplicationStatus.APPLIED && a.getAppliedAt() == null) {
            a.setAppliedAt(now);
        }
        apps.save(a);
        addEvent(a, ApplicationEvent.Type.STATUS, from, target, note, now);
        if (target == ApplicationStatus.APPLIED && followUpDays != null) {
            addReminderInternal(a, now.plus(Duration.ofDays(followUpDays)), followUpText(userId, a), now);
        }
        return detail(a);
    }

    // ---- notes, contacts, interviews, reminders, attachments ----

    @Transactional
    public Detail addNote(UUID userId, UUID id, String text) {
        JobApplication a = owned(userId, id);
        if (text == null || text.isBlank()) {
            throw new InvalidInputException("The note must not be empty.");
        }
        addEvent(a, ApplicationEvent.Type.NOTE, null, null, text.strip(), Instant.now());
        touch(a);
        return detail(a);
    }

    @Transactional
    public Detail addContact(UUID userId, UUID id, String name, String role, String email, String phone, String note) {
        JobApplication a = owned(userId, id);
        capacity(contacts.findByApplicationIdOrderByCreatedAtAsc(a.getId()).size());
        if (name == null || name.isBlank()) {
            throw new InvalidInputException("The contact needs a name.");
        }
        ApplicationContact c = new ApplicationContact();
        c.setApplicationId(a.getId());
        c.setName(enc(name.strip()));
        c.setRole(enc(role));
        c.setEmail(enc(email));
        c.setPhone(enc(phone));
        c.setNote(enc(note));
        contacts.save(c);
        addEvent(a, ApplicationEvent.Type.CONTACT, null, null, name.strip(), Instant.now());
        touch(a);
        return detail(a);
    }

    @Transactional
    public Detail removeContact(UUID userId, UUID id, UUID contactId) {
        JobApplication a = owned(userId, id);
        contacts.delete(contacts.findByIdAndApplicationId(contactId, a.getId()).orElseThrow(() -> new NotFoundException("Contact not found.")));
        return detail(a);
    }

    @Transactional
    public Detail addInterview(UUID userId, UUID id, ApplicationInterview.Kind kind, Instant when, Integer minutes, String location, String notes) {
        JobApplication a = owned(userId, id);
        capacity(interviews.findByApplicationIdOrderByScheduledAtAsc(a.getId()).size());
        if (when == null) {
            throw new InvalidInputException("The interview needs a date and time.");
        }
        ApplicationInterview i = new ApplicationInterview();
        i.setApplicationId(a.getId());
        i.setKind(kind == null ? ApplicationInterview.Kind.OTHER : kind);
        i.setScheduledAt(when);
        i.setDurationMinutes(minutes == null ? 60 : Math.max(5, Math.min(600, minutes)));
        i.setLocation(enc(location));
        i.setNotes(enc(notes));
        interviews.save(i);
        addEvent(a, ApplicationEvent.Type.INTERVIEW, null, null, i.getKind().name(), Instant.now());
        touch(a);
        return detail(a);
    }

    @Transactional
    public Detail removeInterview(UUID userId, UUID id, UUID interviewId) {
        JobApplication a = owned(userId, id);
        interviews.delete(interviews.findByIdAndApplicationId(interviewId, a.getId()).orElseThrow(() -> new NotFoundException("Interview not found.")));
        return detail(a);
    }

    @Transactional
    public Detail addReminder(UUID userId, UUID id, Instant due, String text) {
        JobApplication a = owned(userId, id);
        capacity(reminders.findByApplicationIdOrderByDueAtAsc(a.getId()).size());
        if (due == null || text == null || text.isBlank()) {
            throw new InvalidInputException("A reminder needs a date and a text.");
        }
        addReminderInternal(a, due, text.strip(), Instant.now());
        return detail(a);
    }

    @Transactional
    public Detail completeReminder(UUID userId, UUID id, UUID reminderId, boolean done) {
        JobApplication a = owned(userId, id);
        ApplicationReminder r = reminders.findByIdAndApplicationId(reminderId, a.getId()).orElseThrow(() -> new NotFoundException("Reminder not found."));
        r.setDoneAt(done ? Instant.now() : null);
        reminders.save(r);
        return detail(a);
    }

    @Transactional
    public Detail removeReminder(UUID userId, UUID id, UUID reminderId) {
        JobApplication a = owned(userId, id);
        reminders.delete(reminders.findByIdAndApplicationId(reminderId, a.getId()).orElseThrow(() -> new NotFoundException("Reminder not found.")));
        return detail(a);
    }

    @Transactional
    public Detail addAttachment(UUID userId, UUID id, UUID documentId, String label, String url) {
        JobApplication a = owned(userId, id);
        capacity(attachments.findByApplicationIdOrderByCreatedAtAsc(a.getId()).size());
        ApplicationAttachment att = new ApplicationAttachment();
        att.setApplicationId(a.getId());
        if (documentId != null) {
            GeneratedDocument d = documents.findByIdAndUserId(documentId, userId).orElseThrow(() -> new NotFoundException("Document not found."));
            att.setKind(ApplicationAttachment.Kind.DOCUMENT);
            att.setDocumentId(d.getId());
            att.setLabel(clip(blankToNull(label) != null ? label.strip()
                    : (d.getDocType() == GeneratedDocument.Type.LETTER ? "Cover letter" : "Application email") + " v" + d.getVersion(), 200));
        } else {
            String link = httpOrNull(url);
            if (link == null || label == null || label.isBlank()) {
                throw new InvalidInputException("A link needs a label and an http or https address.");
            }
            att.setKind(ApplicationAttachment.Kind.LINK);
            att.setLabel(clip(label.strip(), 200));
            att.setUrl(link);
        }
        attachments.save(att);
        addEvent(a, ApplicationEvent.Type.ATTACHMENT, null, null, att.getLabel(), Instant.now());
        touch(a);
        return detail(a);
    }

    @Transactional
    public Detail removeAttachment(UUID userId, UUID id, UUID attachmentId) {
        JobApplication a = owned(userId, id);
        attachments.delete(attachments.findByIdAndApplicationId(attachmentId, a.getId()).orElseThrow(() -> new NotFoundException("Attachment not found.")));
        return detail(a);
    }

    // ---- dashboard and calendar ----

    @Transactional(readOnly = true)
    public Dashboard dashboard(UUID userId) {
        List<JobApplication> all = apps.findByUserIdOrderByUpdatedAtDesc(userId);
        Map<ApplicationStatus, Long> counts = new EnumMap<>(ApplicationStatus.class);
        for (ApplicationStatus s : ApplicationStatus.values()) {
            counts.put(s, 0L);
        }
        all.forEach(a -> counts.merge(a.getStatus(), 1L, Long::sum));
        Map<UUID, JobApplication> byId = all.stream().collect(Collectors.toMap(JobApplication::getId, a -> a));
        Instant now = Instant.now();
        List<Reminder> due = reminders.findByUserIdAndDoneAtIsNullOrderByDueAtAsc(userId).stream().limit(8)
                .map(r -> reminderView(r, byId.get(r.getApplicationId()), now)).toList();
        List<UpcomingInterview> upcoming = new ArrayList<>();
        if (!all.isEmpty()) {
            for (ApplicationInterview i : interviews.findByApplicationIdInOrderByScheduledAtAsc(byId.keySet())) {
                JobApplication a = byId.get(i.getApplicationId());
                if (i.getScheduledAt().isAfter(now) && !a.getStatus().isFinal()) {
                    upcoming.add(new UpcomingInterview(a.getId(), a.getTitle(), a.getCompany(), i.getKind().name(), i.getScheduledAt(), dec(i.getLocation())));
                }
            }
        }
        return new Dashboard(counts, all.size(), due, upcoming.stream().limit(5).toList());
    }

    /** Interviews, open reminders and application deadlines as a calendar file; one application, or all of them. */
    @Transactional(readOnly = true)
    public String calendar(UUID userId, UUID applicationId) {
        List<JobApplication> list = applicationId == null ? apps.findByUserIdOrderByUpdatedAtDesc(userId) : List.of(owned(userId, applicationId));
        List<IcsBuilder.Event> out = new ArrayList<>();
        Instant now = Instant.now();
        for (JobApplication a : list) {
            String label = a.getTitle() + (a.getCompany() == null ? "" : " - " + a.getCompany());
            for (ApplicationInterview i : interviews.findByApplicationIdOrderByScheduledAtAsc(a.getId())) {
                out.add(IcsBuilder.Event.timed(i.getId() + "@jobmatcher", "Interview (" + i.getKind().name().toLowerCase() + "): " + label,
                        dec(i.getNotes()), dec(i.getLocation()), i.getScheduledAt(), i.getDurationMinutes(), true));
            }
            for (ApplicationReminder r : reminders.findByApplicationIdOrderByDueAtAsc(a.getId())) {
                if (r.getDoneAt() == null) {
                    out.add(IcsBuilder.Event.timed(r.getId() + "@jobmatcher", dec(r.getText()) + " (" + label + ")", null, null, r.getDueAt(), 30, true));
                }
            }
            if (a.getDeadlineAt() != null && !a.getStatus().isFinal() && a.getStatus().ordinal() < ApplicationStatus.APPLIED.ordinal()) {
                out.add(IcsBuilder.Event.allDay(a.getId() + "-deadline@jobmatcher", "Application deadline: " + label, a.getJobUrl(),
                        a.getDeadlineAt().atZone(ZoneOffset.UTC).toLocalDate()));
            }
        }
        return IcsBuilder.build("JobMatcher applications", out, now);
    }

    // ---- internals ----

    private JobApplication owned(UUID userId, UUID id) {
        return apps.findByIdAndUserId(id, userId).orElseThrow(() -> new NotFoundException("Application not found."));
    }

    private JobOffer visibleJob(UUID userId, UUID jobId) {
        return offers.findById(jobId).filter(o -> o.getOwnerUserId() == null || o.getOwnerUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Job not found."));
    }

    private UUID ownedCv(UUID userId, UUID cvId) {
        return cvs.findByIdAndUserId(cvId, userId).orElseThrow(() -> new NotFoundException("CV not found.")).getId();
    }

    private void touch(JobApplication a) {
        apps.save(a); // refreshes updated_at so recently active applications sort first
    }

    private void capacity(int existing) {
        if (existing >= MAX_ITEMS_PER_APPLICATION) {
            throw new InvalidInputException("This application already has the maximum number of entries of this kind.");
        }
    }

    private void addEvent(JobApplication a, ApplicationEvent.Type type, ApplicationStatus from, ApplicationStatus to, String note, Instant at) {
        ApplicationEvent e = new ApplicationEvent();
        e.setApplicationId(a.getId());
        e.setUserId(a.getUserId());
        e.setType(type);
        e.setFromStatus(from);
        e.setToStatus(to);
        e.setNote(enc(note));
        e.setOccurredAt(at);
        events.save(e);
    }

    private void addReminderInternal(JobApplication a, Instant due, String text, Instant now) {
        ApplicationReminder r = new ApplicationReminder();
        r.setApplicationId(a.getId());
        r.setUserId(a.getUserId());
        r.setDueAt(due);
        r.setText(crypto.encrypt(clip(text, 500)));
        reminders.save(r);
        addEvent(a, ApplicationEvent.Type.REMINDER, null, null, text, now);
        touch(a);
    }

    private String followUpText(UUID userId, JobApplication a) {
        boolean fr = users.findById(userId).map(u -> "fr".equals(u.getPreferredLanguage())).orElse(false);
        return fr ? "Relancer pour cette candidature" : "Follow up on this application";
    }

    private Summary summary(JobApplication a, Instant nextInterview, List<ApplicationReminder> open) {
        Instant now = Instant.now();
        return new Summary(a.getId(), a.getJobId(), a.getTitle(), a.getCompany(), a.getLocation(), a.getJobUrl(), a.getStatus(), a.getAppliedAt(),
                a.getDeadlineAt(), a.getStatusChangedAt(), a.getCreatedAt(), nextInterview, open.isEmpty() ? null : open.get(0).getDueAt(), open.size(),
                (int) open.stream().filter(r -> r.getDueAt().isBefore(now)).count(),
                a.getStatus().next().stream().sorted(Comparator.naturalOrder()).toList());
    }

    private Detail detail(JobApplication a) {
        Instant now = Instant.now();
        List<ApplicationInterview> iv = interviews.findByApplicationIdOrderByScheduledAtAsc(a.getId());
        List<ApplicationReminder> rem = reminders.findByApplicationIdOrderByDueAtAsc(a.getId());
        Instant nextInterview = iv.stream().map(ApplicationInterview::getScheduledAt).filter(t -> t.isAfter(now)).findFirst().orElse(null);
        List<ApplicationReminder> open = rem.stream().filter(r -> r.getDoneAt() == null).toList();
        List<ApplicationStatus> next = a.getStatus().next().stream().sorted(Comparator.naturalOrder()).toList();
        return new Detail(summary(a, nextInterview, open), a.getCvId(), dec(a.getNotes()), next,
                events.findByApplicationIdOrderByOccurredAtAsc(a.getId()).stream()
                        .map(e -> new Event(e.getId(), e.getType().name(), e.getFromStatus(), e.getToStatus(), dec(e.getNote()), e.getOccurredAt())).toList(),
                contacts.findByApplicationIdOrderByCreatedAtAsc(a.getId()).stream()
                        .map(c -> new Contact(c.getId(), dec(c.getName()), dec(c.getRole()), dec(c.getEmail()), dec(c.getPhone()), dec(c.getNote()))).toList(),
                iv.stream().map(i -> new Interview(i.getId(), i.getKind().name(), i.getScheduledAt(), i.getDurationMinutes(), dec(i.getLocation()), dec(i.getNotes()))).toList(),
                rem.stream().map(r -> reminderView(r, a, now)).toList(),
                attachments.findByApplicationIdOrderByCreatedAtAsc(a.getId()).stream()
                        .map(t -> new Attachment(t.getId(), t.getKind().name(), t.getDocumentId(), t.getLabel(), t.getUrl())).toList());
    }

    private Reminder reminderView(ApplicationReminder r, JobApplication a, Instant now) {
        return new Reminder(r.getId(), r.getApplicationId(), a == null ? null : a.getTitle(), r.getDueAt(), dec(r.getText()), r.getDoneAt(),
                r.getDoneAt() == null && r.getDueAt().isBefore(now));
    }

    private String enc(String s) {
        return s == null || s.isBlank() ? null : crypto.encrypt(clip(s.strip(), 10_000));
    }

    private String dec(String s) {
        return s == null ? null : crypto.decrypt(s);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String httpOrNull(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String u = url.strip();
        if (!(u.startsWith("https://") || u.startsWith("http://")) || u.length() > 2000) {
            throw new InvalidInputException("Links must start with http:// or https://.");
        }
        return u;
    }
}
