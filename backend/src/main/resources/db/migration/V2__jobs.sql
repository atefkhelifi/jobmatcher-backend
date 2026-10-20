-- M2: job discovery. job_source and job_offer are a shared catalog (public job data);
-- search_configuration is owned by a user. Unknown values stay NULL, never invented.

CREATE TABLE job_source (
    source_key           VARCHAR(40) PRIMARY KEY,
    last_attempt_at      TIMESTAMPTZ,
    last_success_at      TIMESTAMPTZ,
    last_error           VARCHAR(300),
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    last_item_count      INTEGER,
    total_runs           INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE job_offer (
    id                UUID PRIMARY KEY,
    source_key        VARCHAR(40)  NOT NULL,
    external_id       VARCHAR(300) NOT NULL,
    owner_user_id     UUID REFERENCES app_user (id) ON DELETE CASCADE, -- set for user-submitted URLs, NULL for the shared catalog
    title             VARCHAR(300) NOT NULL,
    company           VARCHAR(300),
    location_text     VARCHAR(300),
    country           VARCHAR(2),
    remote_mode       VARCHAR(10)  NOT NULL DEFAULT 'UNKNOWN',
    description       TEXT         NOT NULL,
    employment_type   VARCHAR(30),
    salary_min        INTEGER,
    salary_max        INTEGER,
    salary_currency   VARCHAR(3),
    posted_at         TIMESTAMPTZ,
    valid_through     TIMESTAMPTZ,
    job_url           VARCHAR(2000) NOT NULL,
    apply_url         VARCHAR(2000),
    language          VARCHAR(5),
    fingerprint       VARCHAR(64)  NOT NULL,
    status            VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    duplicate_of      UUID REFERENCES job_offer (id) ON DELETE SET NULL,
    first_seen_at     TIMESTAMPTZ  NOT NULL,
    last_seen_at      TIMESTAMPTZ  NOT NULL,
    last_verified_at  TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_job_remote CHECK (remote_mode IN ('REMOTE', 'HYBRID', 'ONSITE', 'UNKNOWN')),
    CONSTRAINT ck_job_status CHECK (status IN ('ACTIVE', 'EXPIRED')),
    CONSTRAINT ck_job_salary CHECK (salary_min IS NULL OR salary_max IS NULL OR salary_min <= salary_max)
);
-- One row per posting per source. Owner is part of the key so two users importing the same URL do not collide.
CREATE UNIQUE INDEX uq_job_source_external ON job_offer (source_key, external_id, COALESCE(owner_user_id, '00000000-0000-0000-0000-000000000000'::uuid));
CREATE INDEX ix_job_fingerprint ON job_offer (fingerprint) WHERE duplicate_of IS NULL;
CREATE INDEX ix_job_listing ON job_offer (status, posted_at DESC NULLS LAST) WHERE duplicate_of IS NULL;
CREATE INDEX ix_job_owner ON job_offer (owner_user_id) WHERE owner_user_id IS NOT NULL;
CREATE INDEX ix_job_verify ON job_offer (last_verified_at NULLS FIRST) WHERE status = 'ACTIVE';

CREATE TABLE search_configuration (
    id             UUID PRIMARY KEY,
    user_id        UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name           VARCHAR(120) NOT NULL,
    keywords       VARCHAR(200),
    location       VARCHAR(120),
    remote_only    BOOLEAN NOT NULL DEFAULT FALSE,
    source_keys    TEXT NOT NULL DEFAULT '[]',
    auto_run       BOOLEAN NOT NULL DEFAULT FALSE,
    interval_hours INTEGER NOT NULL DEFAULT 24,
    last_run_at    TIMESTAMPTZ,
    last_new_count INTEGER,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_search_interval CHECK (interval_hours BETWEEN 1 AND 168)
);
CREATE INDEX ix_search_user ON search_configuration (user_id);
CREATE INDEX ix_search_due ON search_configuration (last_run_at NULLS FIRST) WHERE auto_run;
