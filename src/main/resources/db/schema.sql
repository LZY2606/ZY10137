PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;
PRAGMA busy_timeout=10000;

-- Spatial reference of surveys.
CREATE TABLE IF NOT EXISTS site (
    code        TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    lat         REAL NOT NULL,
    lon         REAL NOT NULL
);

-- Survey occasion: a HALF-OPEN interval [start_at, end_at) with explicit timezone
-- on each endpoint. start_at/end_at store ISO-8601 strings with offset;
-- the *_epoch columns enable unambiguous comparison.
CREATE TABLE IF NOT EXISTS survey_period (
    code            TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    start_at        TEXT NOT NULL,
    end_at          TEXT NOT NULL,
    start_epoch     INTEGER NOT NULL,
    end_epoch       INTEGER NOT NULL
);

-- A concrete field-effort session. A session with count_zero=1 documents that
-- observers were present but saw nothing ("observed zero"); a period with no
-- sessions at all is "not surveyed" (missing), which is distinct.
CREATE TABLE IF NOT EXISTS survey_session (
    code            TEXT PRIMARY KEY,
    site_code       TEXT NOT NULL REFERENCES site(code),
    period_code     TEXT NOT NULL REFERENCES survey_period(code),
    start_at        TEXT NOT NULL,
    end_at          TEXT NOT NULL,
    start_epoch     INTEGER NOT NULL,
    end_epoch       INTEGER NOT NULL,
    count_zero      INTEGER NOT NULL DEFAULT 0,
    note            TEXT
);

-- Known individual registry. Individual rows are never deleted by merges:
-- merges are hypothesis-scoped decisions recorded on hypothesis_assignment.
CREATE TABLE IF NOT EXISTS individual (
    code            TEXT PRIMARY KEY,
    label           TEXT NOT NULL,
    marking         TEXT,
    pattern_summary TEXT,
    created_batch   TEXT,
    created_at      TEXT NOT NULL
);

-- Idempotent observation import batches.
CREATE TABLE IF NOT EXISTS import_batch (
    batch_ref       TEXT PRIMARY KEY,
    imported_at     TEXT NOT NULL,
    note            TEXT,
    content_hash    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS observation (
    id                  TEXT PRIMARY KEY,
    batch_ref           TEXT NOT NULL REFERENCES import_batch(batch_ref),
    site_code           TEXT NOT NULL REFERENCES site(code),
    observed_at         TEXT NOT NULL,
    observed_epoch      INTEGER NOT NULL,
    period_code         TEXT REFERENCES survey_period(code),
    mark_fragment       TEXT,
    pattern_summary     TEXT,
    confidence          REAL NOT NULL,
    is_zero             INTEGER NOT NULL DEFAULT 0,
    observer            TEXT,
    note                TEXT,
    content_hash        TEXT NOT NULL,
    UNIQUE (batch_ref, content_hash)
);

-- Raw automatic scoring output. individual_code is a real individual or the
-- sentinel '#NEW#' (possible new individual). Adjustments for spatio-temporal
-- conflicts are recomputed at read time against a hypothesis context and are
-- never written back over the raw score.
CREATE TABLE IF NOT EXISTS candidate (
    observation_id      TEXT NOT NULL REFERENCES observation(id),
    individual_code     TEXT NOT NULL,
    raw_score           REAL NOT NULL,
    mark_component      REAL NOT NULL,
    pattern_component   REAL NOT NULL,
    scored_at           TEXT NOT NULL,
    PRIMARY KEY (observation_id, individual_code)
);

-- Hypothesis lineage. status='DRAFT' has revision (optimistic-concurrency
-- counter, base_version required by clients); status='PUBLISHED' rows are
-- immutable, versioned snapshots.
CREATE TABLE IF NOT EXISTS hypothesis (
    id              TEXT PRIMARY KEY,
    lineage_id      TEXT NOT NULL,
    status          TEXT NOT NULL CHECK (status IN ('DRAFT','PUBLISHED')),
    version         INTEGER,
    revision        INTEGER NOT NULL DEFAULT 0,
    parent_hyp_id   TEXT,
    title           TEXT NOT NULL,
    created_at      TEXT NOT NULL,
    published_at    TEXT,
    note            TEXT,
    UNIQUE (lineage_id, version)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_hypothesis_one_draft
    ON hypothesis(lineage_id) WHERE status='DRAFT';

-- Decisions within a hypothesis. exactly one CONFIRMED row per
-- (hypothesis, observation) is enforced by the partial unique index;
-- DENIED rows coexist as human annotations. merges/splits are recorded
-- here as operations on individual identities inside this hypothesis.
CREATE TABLE IF NOT EXISTS hypothesis_assignment (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    hyp_id              TEXT NOT NULL REFERENCES hypothesis(id),
    observation_id      TEXT REFERENCES observation(id),
    individual_code     TEXT,
    original_individual TEXT,
    decision            TEXT NOT NULL CHECK (decision IN ('CONFIRMED','DENIED','MERGED','SPLIT')),
    reason_code         TEXT CHECK (reason_code IN (
                            'AUTO_CARRIED','MANUAL_REVIEW','MARK_LOST','DUPLICATE_CODE',
                            'DATA_ENTRY_ERROR','OVERRIDE_CONFLICT','MERGE','SPLIT','NEW_INDIVIDUAL')),
    reason_note         TEXT,
    created_at          TEXT NOT NULL,
    created_by          TEXT NOT NULL DEFAULT 'researcher'
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_confirmed_assignment
    ON hypothesis_assignment(hyp_id, observation_id)
    WHERE decision='CONFIRMED';

-- Append-only audit trail of hypothesis edits (used to surface the other
-- reviewer's changes when a base-revision conflict occurs).
CREATE TABLE IF NOT EXISTS hypothesis_change (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    hyp_id              TEXT NOT NULL REFERENCES hypothesis(id),
    revision            INTEGER NOT NULL,
    action              TEXT NOT NULL,
    observation_id      TEXT,
    individual_code     TEXT,
    detail              TEXT,
    actor               TEXT NOT NULL DEFAULT 'researcher',
    created_at          TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_hyp_change ON hypothesis_change(hyp_id, revision);

-- Idempotency key for publication (same draft revision published twice).
CREATE TABLE IF NOT EXISTS publication (
    hyp_id      TEXT PRIMARY KEY REFERENCES hypothesis(id),
    revision    INTEGER NOT NULL,
    published_at TEXT NOT NULL
);
