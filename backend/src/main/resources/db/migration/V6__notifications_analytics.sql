-- M7: in-app notifications (job alerts, reminders, interviews), alert threshold on saved searches, and the country of a
-- tracked role so analytics can group by it.

CREATE TABLE notification (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    type        VARCHAR(20)  NOT NULL,
    code        VARCHAR(40)  NOT NULL,           -- the interface renders the text for this code in the user language
    params_json TEXT         NOT NULL DEFAULT '{}',
    link        VARCHAR(300),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    read_at     TIMESTAMPTZ,
    CONSTRAINT ck_notification_type CHECK (type IN ('NEW_MATCHES', 'REMINDER_DUE', 'INTERVIEW_SOON'))
);
CREATE INDEX ix_notification_user ON notification (user_id, created_at DESC);
CREATE INDEX ix_notification_unread ON notification (user_id) WHERE read_at IS NULL;

ALTER TABLE search_configuration
    ADD COLUMN min_score INTEGER NOT NULL DEFAULT 60,
    ADD CONSTRAINT ck_search_min_score CHECK (min_score BETWEEN 0 AND 100);

ALTER TABLE job_application ADD COLUMN country VARCHAR(2);

ALTER TABLE application_reminder ADD COLUMN notified_at TIMESTAMPTZ;
ALTER TABLE application_interview ADD COLUMN notified_at TIMESTAMPTZ;
CREATE INDEX ix_reminder_due_unnotified ON application_reminder (due_at) WHERE done_at IS NULL AND notified_at IS NULL;
CREATE INDEX ix_interview_unnotified ON application_interview (scheduled_at) WHERE notified_at IS NULL;
