-- BatchOPS schema. UUID primary keys for externally visible entities, bigint for config tables.

CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(150) NOT NULL UNIQUE,
    email           VARCHAR(254) NOT NULL DEFAULT '',
    password_hash   VARCHAR(100) NOT NULL,
    role            VARCHAR(20)  NOT NULL DEFAULT 'user' CHECK (role IN ('user','moderator','admin')),
    is_superuser    BOOLEAN      NOT NULL DEFAULT FALSE,
    email_verified  BOOLEAN      NOT NULL DEFAULT FALSE,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Users may be limited to specific departments (empty = all). Fixes the "every user sees everything" gap.
CREATE TABLE user_departments (
    user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    department  VARCHAR(100) NOT NULL,
    PRIMARY KEY (user_id, department)
);

-- Tokens are stored hashed (SHA-256) and expire; the raw token is only ever returned at login.
CREATE TABLE auth_tokens (
    token_hash   VARCHAR(64)    PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_auth_tokens_user ON auth_tokens(user_id);

-- One table for password reset + email verification, with an attempts counter against code brute force.
CREATE TABLE code_requests (
    request_id  UUID PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    purpose     VARCHAR(20) NOT NULL CHECK (purpose IN ('reset','verify')),
    code_hash   VARCHAR(64)    NOT NULL,
    attempts    INT         NOT NULL DEFAULT 0,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_code_requests_user ON code_requests(user_id, created_at DESC);

CREATE TABLE uploads (
    upload_id           UUID PRIMARY KEY,
    department          VARCHAR(100) NOT NULL,
    filename            VARCHAR(255) NOT NULL,
    mime_type           VARCHAR(100) NOT NULL DEFAULT '',
    status              VARCHAR(20)  NOT NULL DEFAULT 'pending'
                        CHECK (status IN ('pending','processing','published','failed')),
    received_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    notes               TEXT,
    sha256              VARCHAR(64),
    size_bytes          BIGINT       NOT NULL DEFAULT 0,
    -- The source object is immutable; every fix writes a new versioned artifact key.
    source_key          VARCHAR(500) NOT NULL DEFAULT '',
    current_key         VARCHAR(500) NOT NULL DEFAULT '',
    version             INT          NOT NULL DEFAULT 1,
    file_path           VARCHAR(500) NOT NULL DEFAULT '',
    report_path         VARCHAR(500) NOT NULL DEFAULT '',
    report_csv_key      VARCHAR(500) NOT NULL DEFAULT '',
    report_pdf_key      VARCHAR(500) NOT NULL DEFAULT '',
    report_generated_at TIMESTAMPTZ,
    report_meta         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    process_mode        VARCHAR(50)  NOT NULL DEFAULT 'transform_gradebook',
    process_config      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    validation_report   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_by          VARCHAR(150),
    row_version         BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_uploads_status_dept ON uploads(status, department);
CREATE INDEX idx_uploads_received ON uploads(received_at DESC);
-- Idempotent ingest: the same file for the same department is deduplicated while it is live.
CREATE UNIQUE INDEX uq_uploads_dedupe ON uploads(department, sha256) WHERE sha256 IS NOT NULL AND status <> 'failed';

CREATE TABLE department_sources (
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(120) NOT NULL UNIQUE,
    code             VARCHAR(50)  NOT NULL UNIQUE,
    description      TEXT         NOT NULL DEFAULT '',
    schedule_hint    VARCHAR(120) NOT NULL DEFAULT '',
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    last_ingested_at TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE department_records (
    id                 BIGSERIAL PRIMARY KEY,
    source_id          BIGINT       NOT NULL REFERENCES department_sources(id) ON DELETE CASCADE,
    student_id         VARCHAR(40)  NOT NULL,
    student_name       VARCHAR(120) NOT NULL,
    class_name         VARCHAR(50)  NOT NULL DEFAULT '',
    score              NUMERIC(6,2),
    attendance_percent NUMERIC(5,2),
    status             VARCHAR(40)  NOT NULL DEFAULT '',
    recorded_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    notes              TEXT,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_dept_records_source ON department_records(source_id, recorded_at DESC);
CREATE INDEX idx_dept_records_student ON department_records(student_id);

CREATE TABLE jobs (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(100) NOT NULL UNIQUE,
    job_type      VARCHAR(20)  NOT NULL DEFAULT 'python' CHECK (job_type IN ('shell','http','python')),
    config        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    schedule_cron VARCHAR(100),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE job_runs (
    id           BIGSERIAL PRIMARY KEY,
    job_id       BIGINT      NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    upload_id    UUID        REFERENCES uploads(upload_id) ON DELETE CASCADE,
    run_id       UUID        NOT NULL UNIQUE,
    status       VARCHAR(20) NOT NULL DEFAULT 'queued'
                 CHECK (status IN ('queued','running','success','failed','retrying')),
    started_at   TIMESTAMPTZ,
    finished_at  TIMESTAMPTZ,
    duration_ms  INT,
    exit_code    INT,
    logs         TEXT        NOT NULL DEFAULT '',
    details      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    retry_count  INT         NOT NULL DEFAULT 0,
    max_retries  INT         NOT NULL DEFAULT 3
);
CREATE INDEX idx_job_runs_upload_job ON job_runs(upload_id, job_id);
CREATE INDEX idx_job_runs_status ON job_runs(status);
CREATE INDEX idx_job_runs_started ON job_runs(started_at DESC);

CREATE TABLE known_errors (
    error_id   UUID PRIMARY KEY,
    name       VARCHAR(120) NOT NULL DEFAULT '',
    pattern    TEXT         NOT NULL,
    fix        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    examples   JSONB        NOT NULL DEFAULT '[]'::jsonb,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE incidents (
    incident_id         UUID PRIMARY KEY,
    upload_id           UUID        NOT NULL REFERENCES uploads(upload_id) ON DELETE CASCADE,
    job_run_id          BIGINT      REFERENCES job_runs(id) ON DELETE SET NULL,
    matched_known_error UUID        REFERENCES known_errors(error_id) ON DELETE SET NULL,
    -- fingerprint = hash(upload, step, normalized error); repeat failures bump occurrence_count.
    fingerprint         VARCHAR(32)    NOT NULL,
    occurrence_count    INT         NOT NULL DEFAULT 1,
    error               TEXT        NOT NULL,
    root_cause          TEXT,
    corrective_action   TEXT,
    impact_summary      TEXT,
    analysis_notes      TEXT,
    resolution_report   TEXT,
    severity            VARCHAR(20) NOT NULL DEFAULT 'medium' CHECK (severity IN ('low','medium','high','critical')),
    category            VARCHAR(100),
    detection_source    VARCHAR(100),
    timeline            JSONB       NOT NULL DEFAULT '[]'::jsonb,
    auto_retry_count    INT         NOT NULL DEFAULT 0,
    max_auto_retries    INT         NOT NULL DEFAULT 2,
    manual_retries      INT         NOT NULL DEFAULT 0,
    pending_fixes       JSONB       NOT NULL DEFAULT '[]'::jsonb,
    archived_at         TIMESTAMPTZ,
    resolved_by         VARCHAR(100),
    resolved_at         TIMESTAMPTZ,
    state               VARCHAR(20) NOT NULL DEFAULT 'open' CHECK (state IN ('open','in_progress','resolved')),
    assignee            VARCHAR(100),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_incidents_state_upload ON incidents(state, upload_id);
CREATE INDEX idx_incidents_upload ON incidents(upload_id);
-- At most one live incident per fingerprint: concurrent failures collapse instead of duplicating.
CREATE UNIQUE INDEX uq_incident_live_fingerprint ON incidents(fingerprint) WHERE state <> 'resolved';

CREATE TABLE tickets (
    ticket_id        UUID PRIMARY KEY,
    incident_id      UUID        REFERENCES incidents(incident_id) ON DELETE CASCADE,
    source           VARCHAR(20) NOT NULL DEFAULT 'system' CHECK (source IN ('system','manual')),
    status           VARCHAR(20) NOT NULL DEFAULT 'open' CHECK (status IN ('open','in_progress','resolved','closed')),
    assignee         VARCHAR(100),
    created_by       VARCHAR(100),
    resolved_by      VARCHAR(100),
    resolution_type  VARCHAR(20) CHECK (resolution_type IN ('automatic','manual')),
    resolution_notes TEXT,
    timeline         JSONB       NOT NULL DEFAULT '[]'::jsonb,
    title            VARCHAR(255),
    description      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at      TIMESTAMPTZ
);
CREATE INDEX idx_tickets_status_assignee ON tickets(status, assignee);
CREATE INDEX idx_tickets_source_status ON tickets(source, status);

-- ---------------------------------------------------------------------------
-- Event backbone tables
-- ---------------------------------------------------------------------------

-- Transactional outbox: state change + event are committed atomically, a relay publishes to Kafka.
CREATE TABLE outbox (
    id           BIGSERIAL PRIMARY KEY,
    event_id     UUID         NOT NULL UNIQUE,
    topic        VARCHAR(120) NOT NULL,
    msg_key      VARCHAR(120) NOT NULL,
    payload      JSONB        NOT NULL,
    headers      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox(id) WHERE published_at IS NULL;

-- Idempotent consumers: (consumer, event_id) is inserted in the same transaction as the side effects.
CREATE TABLE processed_events (
    consumer     VARCHAR(80) NOT NULL,
    event_id     UUID        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

CREATE TABLE dead_letters (
    id           BIGSERIAL PRIMARY KEY,
    event_id     UUID         NOT NULL,
    upload_id    UUID,
    stage        VARCHAR(40)  NOT NULL,
    topic        VARCHAR(120) NOT NULL,
    msg_key      VARCHAR(120),
    payload      JSONB        NOT NULL,
    error_class  VARCHAR(200),
    error        TEXT,
    attempts     INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    replayed_at  TIMESTAMPTZ
);
CREATE INDEX idx_dead_letters_pending ON dead_letters(created_at) WHERE replayed_at IS NULL;

-- Scheduler de-duplication across replicas without Redis: one row per (job, fire time).
CREATE TABLE job_fires (
    job_id    BIGINT      NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    fire_time TIMESTAMPTZ NOT NULL,
    fired_by  VARCHAR(80),
    PRIMARY KEY (job_id, fire_time)
);
