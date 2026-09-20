CREATE TABLE IF NOT EXISTS import_batches (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  batch_id TEXT NOT NULL UNIQUE,
  source TEXT,
  payload_hash TEXT NOT NULL,
  imported_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sites (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  code TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  latitude REAL NOT NULL,
  longitude REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS surveys (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  code TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  zone_id TEXT NOT NULL,
  start_at TEXT NOT NULL,
  end_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS survey_sites (
  survey_id INTEGER NOT NULL REFERENCES surveys(id),
  site_id INTEGER NOT NULL REFERENCES sites(id),
  PRIMARY KEY (survey_id, site_id)
);

CREATE TABLE IF NOT EXISTS individuals (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  code TEXT NOT NULL UNIQUE,
  display_name TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'PROPOSED', 'MERGED')),
  created_revision_id INTEGER,
  merged_into_id INTEGER,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS observations (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  code TEXT NOT NULL UNIQUE,
  batch_id TEXT NOT NULL,
  site_id INTEGER NOT NULL REFERENCES sites(id),
  survey_id INTEGER NOT NULL REFERENCES surveys(id),
  observed_at TEXT NOT NULL,
  uncertainty_seconds INTEGER NOT NULL DEFAULT 0,
  marker_fragment TEXT NOT NULL,
  pattern_summary TEXT NOT NULL,
  confidence REAL NOT NULL,
  observer TEXT NOT NULL,
  baseline_individual_code TEXT
);

CREATE TABLE IF NOT EXISTS candidate_scores (
  observation_id INTEGER NOT NULL REFERENCES observations(id),
  individual_id INTEGER NOT NULL REFERENCES individuals(id),
  score REAL NOT NULL,
  auto_state TEXT NOT NULL CHECK (auto_state IN ('CANDIDATE', 'REJECTED')),
  conflict_reason TEXT,
  distance_m REAL,
  elapsed_seconds REAL,
  required_speed_mps REAL,
  calculated_at TEXT NOT NULL,
  PRIMARY KEY (observation_id, individual_id)
);

CREATE TABLE IF NOT EXISTS hypothesis_revisions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  revision_no INTEGER NOT NULL,
  based_on_revision_id INTEGER,
  status TEXT NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED')),
  note TEXT,
  author TEXT NOT NULL,
  client_key TEXT UNIQUE,
  version INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL,
  published_at TEXT
);

CREATE TABLE IF NOT EXISTS revision_items (
  revision_id INTEGER NOT NULL REFERENCES hypothesis_revisions(id),
  observation_id INTEGER NOT NULL REFERENCES observations(id),
  individual_id INTEGER REFERENCES individuals(id),
  PRIMARY KEY (revision_id, observation_id)
);

CREATE TABLE IF NOT EXISTS candidate_decisions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  revision_id INTEGER NOT NULL REFERENCES hypothesis_revisions(id),
  observation_id INTEGER NOT NULL REFERENCES observations(id),
  individual_id INTEGER NOT NULL REFERENCES individuals(id),
  decision TEXT NOT NULL CHECK (decision IN ('CONFIRMED', 'DENIED')),
  reason_code TEXT NOT NULL,
  reason_detail TEXT,
  author TEXT NOT NULL,
  created_at TEXT NOT NULL,
  UNIQUE (revision_id, observation_id, individual_id)
);

CREATE TABLE IF NOT EXISTS identity_operations (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  revision_id INTEGER NOT NULL REFERENCES hypothesis_revisions(id),
  operation_type TEXT NOT NULL,
  from_individual_id INTEGER REFERENCES individuals(id),
  to_individual_id INTEGER REFERENCES individuals(id),
  observation_id INTEGER REFERENCES observations(id),
  reason_code TEXT NOT NULL,
  reason_detail TEXT,
  author TEXT NOT NULL,
  base_version INTEGER NOT NULL,
  next_version INTEGER NOT NULL,
  payload_json TEXT,
  client_key TEXT UNIQUE,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS published_assignments (
  revision_id INTEGER NOT NULL REFERENCES hypothesis_revisions(id),
  observation_id INTEGER NOT NULL REFERENCES observations(id),
  individual_id INTEGER NOT NULL REFERENCES individuals(id),
  PRIMARY KEY (revision_id, observation_id)
);

CREATE TABLE IF NOT EXISTS revision_client_keys (
  client_key TEXT PRIMARY KEY,
  revision_id INTEGER NOT NULL REFERENCES hypothesis_revisions(id)
);

CREATE TABLE IF NOT EXISTS publish_client_keys (
  client_key TEXT PRIMARY KEY,
  revision_id INTEGER NOT NULL REFERENCES hypothesis_revisions(id),
  published_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS review_conflicts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  revision_id INTEGER NOT NULL REFERENCES hypothesis_revisions(id),
  submitted_base_version INTEGER NOT NULL,
  current_version INTEGER NOT NULL,
  submitted_payload_json TEXT NOT NULL,
  current_context_json TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_observations_time ON observations(observed_at);
CREATE INDEX IF NOT EXISTS idx_revision_items_individual ON revision_items(revision_id, individual_id);
CREATE INDEX IF NOT EXISTS idx_published_individual ON published_assignments(revision_id, individual_id);
