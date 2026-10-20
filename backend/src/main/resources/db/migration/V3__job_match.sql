-- M3: stored match results. One row per (CV version, job). The report is encrypted because it quotes the CV.

CREATE TABLE job_match (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    cv_id       UUID NOT NULL REFERENCES cv_document (id) ON DELETE CASCADE,
    job_id      UUID NOT NULL REFERENCES job_offer (id) ON DELETE CASCADE,
    score       INTEGER,                 -- NULL when the offer and profile share too little known information
    coverage    INTEGER NOT NULL,        -- share of the weighting that could be evaluated, 0-100
    stamp       VARCHAR(100) NOT NULL,   -- CV, preferences and job versions the result was computed from
    report_json TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_job_match UNIQUE (cv_id, job_id),
    CONSTRAINT ck_match_score CHECK (score IS NULL OR score BETWEEN 0 AND 100),
    CONSTRAINT ck_match_coverage CHECK (coverage BETWEEN 0 AND 100)
);
CREATE INDEX ix_job_match_rank ON job_match (user_id, cv_id, score DESC);
CREATE INDEX ix_job_match_job ON job_match (job_id);
