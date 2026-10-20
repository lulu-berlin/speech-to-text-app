-- Current backend schema (SQLite), consolidated from the live database at main 52e99c3b.
--
-- USE THIS FILE ONLY TO CREATE A FRESH, RUST-OWNED DATABASE.
-- Never run it against an existing Kotlin/Flyway database: `CREATE TABLE` would fail (or
-- `IF NOT EXISTS` would silently accept drift) instead of validating the file.
--
-- Opening an existing database file (the Kotlin app's `stt.db`):
--   * open it unchanged, never create or alter tables;
--   * validate before writing: `flyway_schema_history` exists and its highest installed
--     version is 12, and every table/column below exists with these names;
--   * refuse to start (naming the file) when the validation fails.
-- If the Kotlin app must later reopen a Rust-created database, either populate
-- `flyway_schema_history` with a proper baseline row for the real migrations, or (the safe
-- route) keep running the real Flyway migrations. There is no migration-free shortcut.
--
-- Connection pragmas the app sets (not schema): journal_mode=WAL, foreign_keys=ON,
-- journal_size_limit=67108864 on the writer. One writer connection; a fresh read-only
-- connection per read transaction. Timestamps are INTEGER epoch milliseconds UTC.

CREATE TABLE tasks (
    id                  INTEGER PRIMARY KEY,
    original_filename   TEXT    NOT NULL,
    content_type        TEXT    NOT NULL,
    duration_ms         INTEGER,
    status              TEXT    NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELED')),
    phase               TEXT    CHECK (phase IN ('CHUNKING', 'TRANSCRIBING', 'DIARIZING')),
    queue_position      INTEGER,
    language            TEXT    NOT NULL DEFAULT 'en',
    speakers_mode       TEXT    NOT NULL CHECK (speakers_mode IN ('auto', 'off', 'fixed')),
    speakers_count      INTEGER,
    max_speakers        INTEGER NOT NULL DEFAULT 8,
    model               TEXT    NOT NULL DEFAULT 'large-v3-turbo-q5_0',
    progress            REAL    NOT NULL DEFAULT 0,
    error               TEXT,
    created_at          INTEGER NOT NULL,
    started_at          INTEGER,
    finished_at         INTEGER,
    diarization_stage   TEXT    CHECK (diarization_stage IN ('EMBEDDING', 'CLUSTERING')),
    diarization_progress REAL
);

-- One metadata row per task; the unique sha256 is the duplicate rule (at most one task per
-- audio content). The audio itself lives in original_audio (until chunking) and in
-- audio_header/audio_second (the canonical clip) afterwards.
CREATE TABLE audio (
    task_id    INTEGER PRIMARY KEY REFERENCES tasks (id) ON DELETE CASCADE,
    size_bytes INTEGER NOT NULL,
    sha256     TEXT    NOT NULL UNIQUE
);

-- The upload's original bytes, staged while arriving (task_id NULL) and attached to the new
-- task in the creating transaction; deleted once the canonical clip is stored.
CREATE TABLE original_audio (
    id      INTEGER PRIMARY KEY,
    task_id INTEGER UNIQUE REFERENCES tasks (id) ON DELETE CASCADE
);

-- Original bytes in upload order, windows of at most 1 MiB; every part but the last is full.
CREATE TABLE original_audio_part (
    original_id INTEGER NOT NULL REFERENCES original_audio (id) ON DELETE CASCADE,
    seq         INTEGER NOT NULL CHECK (seq >= 0),
    bytes       BLOB    NOT NULL,
    PRIMARY KEY (original_id, seq)
);

-- The canonical clip: a deconstructed 16 kHz mono FLAC. The header is fLaC + metadata; the
-- frames live four per row (4000 samples each), row k covering [k*1000, (k+1)*1000) ms.
-- Header + rows in order are the encoded file byte for byte.
CREATE TABLE audio_header (
    task_id INTEGER PRIMARY KEY REFERENCES tasks (id) ON DELETE CASCADE,
    header  BLOB    NOT NULL
);

CREATE TABLE audio_second (
    task_id INTEGER NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    second  INTEGER NOT NULL CHECK (second >= 0),
    frames  INTEGER NOT NULL CHECK (frames >= 1),
    bytes   BLOB    NOT NULL,
    PRIMARY KEY (task_id, second)
);

-- Chunk windows into the canonical clip; PENDING until its segments are stored, then DONE.
CREATE TABLE chunks (
    task_id  INTEGER NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    idx      INTEGER NOT NULL,
    start_ms INTEGER NOT NULL CHECK (start_ms >= 0),
    end_ms   INTEGER NOT NULL CHECK (end_ms >= 0),
    status   TEXT    NOT NULL CHECK (status IN ('PENDING', 'DONE')),
    PRIMARY KEY (task_id, idx)
);

-- One speaker per (task, 0-based id); speaker is the stored name ("Speaker N" until renamed).
CREATE TABLE speakers (
    task_id    INTEGER NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    speaker_id INTEGER NOT NULL CHECK (speaker_id >= 0),
    speaker    TEXT    NOT NULL,
    PRIMARY KEY (task_id, speaker_id)
);

-- Transcript segments; speaker_id is NULL until diarization and with speakers off. Text is
-- stored exactly as whisper-cli wrote it; trimming happens on the way out.
CREATE TABLE segments (
    id         INTEGER PRIMARY KEY,
    task_id    INTEGER NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    chunk_idx  INTEGER NOT NULL,
    seq        INTEGER NOT NULL,
    start_ms   INTEGER NOT NULL CHECK (start_ms >= 0),
    end_ms     INTEGER NOT NULL CHECK (end_ms >= 0),
    text       TEXT    NOT NULL,
    speaker_id INTEGER,
    UNIQUE (task_id, chunk_idx, seq),
    FOREIGN KEY (task_id, speaker_id) REFERENCES speakers (task_id, speaker_id)
);

-- Speaker folds, resolved at read time; flat: an into_id is never itself a merged_id here.
CREATE TABLE speaker_merge (
    task_id   INTEGER NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    merged_id INTEGER NOT NULL,
    into_id   INTEGER NOT NULL,
    PRIMARY KEY (task_id, merged_id)
);

-- Single-row queue flag.
CREATE TABLE queue_state (
    id     INTEGER NOT NULL CHECK (id = 1),
    paused INTEGER NOT NULL CHECK (paused IN (0, 1)),
    PRIMARY KEY (id)
);

-- Single-row settings; the engine reads them when it claims a task RUNNING.
CREATE TABLE settings (
    id               INTEGER NOT NULL,
    device           TEXT    NOT NULL,
    threads          INTEGER,
    default_language TEXT    NOT NULL,
    speakers_mode    TEXT    NOT NULL,
    speakers_count   INTEGER,
    max_speakers     INTEGER NOT NULL,
    device_fallback  TEXT,
    PRIMARY KEY (id),
    CHECK (id = 1),
    CHECK (device IN ('auto', 'cpu') OR device LIKE 'gpu:%'),
    CHECK (threads IS NULL OR threads >= 1),
    CHECK (speakers_mode IN ('auto', 'off', 'fixed')),
    CHECK (speakers_count IS NULL OR speakers_count BETWEEN 1 AND 20),
    CHECK (max_speakers BETWEEN 1 AND 20),
    CHECK (device_fallback IS NULL OR device_fallback LIKE 'gpu:%')
);

-- Fresh-database row values: the settings row is seeded with the defaults. `queue_state`
-- intentionally has no row on a fresh database (the app treats a missing row as "not paused"
-- and creates it on the first pause); a `(1, 0)` row is behaviorally equivalent.
INSERT OR IGNORE INTO settings
    (id, device, threads, default_language, speakers_mode, speakers_count, max_speakers, device_fallback)
VALUES
    (1, 'auto', NULL, 'auto', 'auto', NULL, 8, NULL);
