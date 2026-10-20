-- M5: application tracking. A snapshot of the role (title, company, link) is kept on the application so the tracker keeps
-- working after the offer expires or is removed. Free text the user writes, and third-party contact details, are encrypted.

CREATE TABLE job_application (
    id                UUID PRIMARY KEY,
    user_id           UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    job_id            UUID REFERENCES job_offer (id) ON DELETE SET NULL,
    cv_id             UUID REFERENCES cv_document (id) ON DELETE SET NULL,
    title             VARCHAR(300) NOT NULL,
    company           VARCHAR(300),
    location          VARCHAR(300),
    job_url           VARCHAR(2000),
    fingerprint       VARCHAR(64)  NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    applied_at        TIMESTAMPTZ,
    deadline_at       TIMESTAMPTZ,
    notes             TEXT,                       -- encrypted
    status_changed_at TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_application_status CHECK (status IN ('DISCOVERED', 'TO_REVIEW', 'SHORTLISTED', 'PREPARING', 'APPLIED',
                                                       'INTERVIEW', 'ASSESSMENT', 'OFFER', 'REJECTED', 'WITHDRAWN'))
);
CREATE INDEX ix_application_user_status ON job_application (user_id, status);
CREATE INDEX ix_application_user_fp ON job_application (user_id, fingerprint);
CREATE INDEX ix_application_user_job ON job_application (user_id, job_id);

CREATE TABLE application_event (
    id             UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES job_application (id) ON DELETE CASCADE,
    user_id        UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    type           VARCHAR(20) NOT NULL,
    from_status    VARCHAR(20),
    to_status      VARCHAR(20),
    note           TEXT,                          -- encrypted
    occurred_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_event_type CHECK (type IN ('CREATED', 'STATUS', 'NOTE', 'CONTACT', 'INTERVIEW', 'REMINDER', 'ATTACHMENT'))
);
CREATE INDEX ix_event_application ON application_event (application_id, occurred_at);

CREATE TABLE application_contact (
    id             UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES job_application (id) ON DELETE CASCADE,
    name           TEXT NOT NULL,                 -- encrypted
    role           TEXT,                          -- encrypted
    email          TEXT,                          -- encrypted
    phone          TEXT,                          -- encrypted
    note           TEXT,                          -- encrypted
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_contact_application ON application_contact (application_id);

CREATE TABLE application_interview (
    id               UUID PRIMARY KEY,
    application_id   UUID NOT NULL REFERENCES job_application (id) ON DELETE CASCADE,
    kind             VARCHAR(20) NOT NULL,
    scheduled_at     TIMESTAMPTZ NOT NULL,
    duration_minutes INTEGER NOT NULL DEFAULT 60,
    location         TEXT,                        -- encrypted: address or video link
    notes            TEXT,                        -- encrypted
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_interview_kind CHECK (kind IN ('PHONE', 'VIDEO', 'ONSITE', 'TECHNICAL', 'HR', 'OTHER')),
    CONSTRAINT ck_interview_duration CHECK (duration_minutes BETWEEN 5 AND 600)
);
CREATE INDEX ix_interview_application ON application_interview (application_id, scheduled_at);

CREATE TABLE application_reminder (
    id             UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES job_application (id) ON DELETE CASCADE,
    user_id        UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    due_at         TIMESTAMPTZ NOT NULL,
    text           TEXT NOT NULL,                 -- encrypted
    done_at        TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_reminder_open ON application_reminder (user_id, due_at) WHERE done_at IS NULL;
CREATE INDEX ix_reminder_application ON application_reminder (application_id);

CREATE TABLE application_attachment (
    id             UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES job_application (id) ON DELETE CASCADE,
    kind           VARCHAR(10) NOT NULL,          -- DOCUMENT (a letter or email version) or LINK
    document_id    UUID REFERENCES generated_document (id) ON DELETE SET NULL,
    label          VARCHAR(200) NOT NULL,
    url            VARCHAR(2000),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_attachment_kind CHECK (kind IN ('DOCUMENT', 'LINK'))
);
CREATE INDEX ix_attachment_application ON application_attachment (application_id);
