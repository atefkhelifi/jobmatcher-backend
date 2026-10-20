-- M4: generated documents (letters, application emails) with version history, and public HR contacts per offer.

CREATE TABLE generated_document (
    id             UUID PRIMARY KEY,
    user_id        UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    job_id         UUID NOT NULL REFERENCES job_offer (id) ON DELETE CASCADE,
    cv_id          UUID REFERENCES cv_document (id) ON DELETE SET NULL,
    doc_type       VARCHAR(10) NOT NULL,
    language       VARCHAR(5)  NOT NULL,
    version        INTEGER     NOT NULL,
    source         VARCHAR(10) NOT NULL,        -- GENERATED or EDITED
    generator      VARCHAR(80) NOT NULL,        -- template, or ai:<model>
    params_json    TEXT        NOT NULL DEFAULT '{}',
    subject        TEXT,                        -- encrypted; emails only
    body           TEXT        NOT NULL,        -- encrypted
    facts_json     TEXT        NOT NULL,        -- encrypted: the CV facts the text is allowed to rely on
    paragraph_facts_json TEXT  NOT NULL DEFAULT '[]', -- which facts each generated paragraph cites
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_doc_type CHECK (doc_type IN ('LETTER', 'EMAIL')),
    CONSTRAINT ck_doc_source CHECK (source IN ('GENERATED', 'EDITED')),
    CONSTRAINT uq_doc_version UNIQUE (user_id, job_id, doc_type, version)
);
CREATE INDEX ix_doc_job ON generated_document (user_id, job_id, doc_type, version DESC);

CREATE TABLE hr_contact (
    id            UUID PRIMARY KEY,
    job_id        UUID NOT NULL REFERENCES job_offer (id) ON DELETE CASCADE,
    kind          VARCHAR(10)  NOT NULL,        -- EMAIL, PERSON or URL
    value         VARCHAR(500) NOT NULL,        -- the address, name or link exactly as published
    name          VARCHAR(200),
    title         VARCHAR(200),
    source_url    VARCHAR(2000) NOT NULL,       -- where it was published
    source_kind   VARCHAR(20)  NOT NULL,        -- AD_TEXT or OFFICIAL_PAGE
    verified      BOOLEAN      NOT NULL,        -- published in the ad itself or on the employer's own domain
    discovered_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_contact_kind CHECK (kind IN ('EMAIL', 'PERSON', 'URL')),
    CONSTRAINT ck_contact_source CHECK (source_kind IN ('AD_TEXT', 'OFFICIAL_PAGE')),
    CONSTRAINT uq_contact UNIQUE (job_id, kind, value)
);
CREATE INDEX ix_contact_job ON hr_contact (job_id);

-- When a contact search last ran for an offer, so "nothing found" can be told apart from "never looked".
CREATE TABLE contact_search (
    job_id       UUID NOT NULL REFERENCES job_offer (id) ON DELETE CASCADE,
    user_id      UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    searched_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    pages_read   INTEGER     NOT NULL DEFAULT 0,
    note         VARCHAR(300),
    PRIMARY KEY (job_id, user_id)
);
