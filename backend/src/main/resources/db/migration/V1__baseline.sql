-- JobMatcher AI baseline: identity, profile, CVs, CV analyses.
-- All user-owned tables carry user_id and are only read through owner-scoped queries.

CREATE TABLE app_user (
    id                 UUID PRIMARY KEY,
    email              VARCHAR(254) NOT NULL,
    password_hash      VARCHAR(255) NOT NULL,
    display_name       VARCHAR(120) NOT NULL,
    preferred_language VARCHAR(5)   NOT NULL DEFAULT 'en',
    consent_at         TIMESTAMPTZ  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_user_email UNIQUE (email),
    CONSTRAINT ck_app_user_lang CHECK (preferred_language IN ('en', 'fr'))
);

CREATE TABLE candidate_profile (
    id                  UUID PRIMARY KEY,
    user_id             UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    desired_titles      TEXT NOT NULL DEFAULT '[]',
    desired_technologies TEXT NOT NULL DEFAULT '[]',
    seniority           VARCHAR(20),
    salary_min          INTEGER,
    salary_max          INTEGER,
    salary_currency     VARCHAR(3),
    contract_types      TEXT NOT NULL DEFAULT '[]',
    work_modes          TEXT NOT NULL DEFAULT '[]',
    preferred_countries TEXT NOT NULL DEFAULT '[]',
    preferred_locations TEXT NOT NULL DEFAULT '[]',
    willing_to_relocate BOOLEAN NOT NULL DEFAULT FALSE,
    needs_visa_sponsorship BOOLEAN,
    work_authorization  TEXT NOT NULL DEFAULT '[]',
    notify_new_matches  BOOLEAN NOT NULL DEFAULT TRUE,
    notify_reminders    BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_candidate_profile_user UNIQUE (user_id),
    CONSTRAINT ck_salary_range CHECK (salary_min IS NULL OR salary_max IS NULL OR salary_min <= salary_max)
);

CREATE TABLE cv_document (
    id             UUID PRIMARY KEY,
    user_id        UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    label          VARCHAR(160) NOT NULL,
    source_type    VARCHAR(10)  NOT NULL,
    original_filename VARCHAR(255),
    media_type     VARCHAR(120),
    size_bytes     BIGINT,
    language       VARCHAR(5),
    extracted_text TEXT NOT NULL,             -- AES-GCM encrypted at the application layer
    parsed_json    TEXT NOT NULL DEFAULT '{}', -- structured profile, editable by the user
    reviewed       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_cv_source CHECK (source_type IN ('PDF', 'DOCX', 'TEXT'))
);
CREATE INDEX ix_cv_document_user ON cv_document (user_id, created_at DESC);

CREATE TABLE cv_analysis (
    id          UUID PRIMARY KEY,
    cv_id       UUID NOT NULL REFERENCES cv_document (id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    overall_score INTEGER NOT NULL,
    report_json TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_cv_analysis_score CHECK (overall_score BETWEEN 0 AND 100)
);
CREATE INDEX ix_cv_analysis_cv ON cv_analysis (cv_id, created_at DESC);
