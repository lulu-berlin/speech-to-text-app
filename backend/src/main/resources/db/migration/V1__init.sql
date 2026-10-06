-- Roadmap item 03: full schema (ADR 0019). Later items never edit this file;
-- schema changes arrive as new versioned migrations.
-- Timestamps (created_at/started_at/finished_at) are INTEGER epoch millis UTC.

CREATE TABLE tasks (
    id               INTEGER PRIMARY KEY,
    original_filename TEXT    NOT NULL,
    content_type      TEXT    NOT NULL,
    duration_ms       INTEGER,
    status            TEXT    NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELED')),
    phase             TEXT    CHECK (phase IN ('CHUNKING', 'TRANSCRIBING', 'DIARIZING')),
    queue_position    INTEGER,
    language          TEXT    NOT NULL DEFAULT 'en',
    speakers_mode     TEXT    NOT NULL CHECK (speakers_mode IN ('auto', 'off', 'fixed')),
    speakers_count    INTEGER,
    max_speakers      INTEGER NOT NULL DEFAULT 8,
    model             TEXT    NOT NULL DEFAULT 'large-v3-turbo-q5_0',
    progress          REAL    NOT NULL DEFAULT 0,
    error             TEXT,
    created_at        INTEGER NOT NULL,
    started_at        INTEGER,
    finished_at       INTEGER
);

-- Audio metadata, one row per task; the payload lives in audio_parts (ADR 0018).
CREATE TABLE audio (
    task_id    INTEGER PRIMARY KEY REFERENCES tasks (id) ON DELETE CASCADE,
    size_bytes INTEGER NOT NULL,
    sha256     TEXT    NOT NULL
);

-- Audio payload as fixed 1 MiB pages (ADR 0018): constant-memory writes/reads.
CREATE TABLE audio_parts (
    task_id INTEGER NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    part_no INTEGER NOT NULL,
    part    BLOB    NOT NULL,
    PRIMARY KEY (task_id, part_no)
);

-- ADR 0004: a task resumes from its last DONE chunk, so chunks persist as PENDING/DONE.
CREATE TABLE chunks (
    task_id  INTEGER NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    idx      INTEGER NOT NULL,
    start_ms INTEGER NOT NULL CHECK (start_ms >= 0),
    end_ms   INTEGER NOT NULL CHECK (end_ms >= 0),
    status   TEXT    NOT NULL CHECK (status IN ('PENDING', 'DONE')),
    PRIMARY KEY (task_id, idx)
);

CREATE TABLE segments (
    id        INTEGER PRIMARY KEY,
    task_id   INTEGER NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    chunk_idx INTEGER NOT NULL,
    seq       INTEGER NOT NULL,
    start_ms  INTEGER NOT NULL CHECK (start_ms >= 0),
    end_ms    INTEGER NOT NULL CHECK (end_ms >= 0),
    text      TEXT    NOT NULL,
    speaker   TEXT
);

-- Single-row table: id is always 1 (CHECK + PRIMARY KEY allow exactly one row).
CREATE TABLE queue_state (
    id     INTEGER NOT NULL CHECK (id = 1),
    paused INTEGER NOT NULL CHECK (paused IN (0, 1)),
    PRIMARY KEY (id)
);