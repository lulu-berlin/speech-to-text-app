-- Roadmap item 09 (ADR 0028): one row per speaker of a task; speaker_id is the 0-based index the
-- diarization assigns, speaker its name (written by the app as "Speaker N", N = speaker_id + 1).
CREATE TABLE speakers (
    task_id    INTEGER NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    speaker_id INTEGER NOT NULL CHECK (speaker_id >= 0),
    speaker    TEXT    NOT NULL,
    PRIMARY KEY (task_id, speaker_id)
);

-- segments.speaker (free text, never written) becomes speaker_id, a reference to its task's speaker.
-- SQLite cannot change a column or add a foreign key in place, so the table is rebuilt; speaker_id is
-- NULL until diarization and stays NULL with speakers_mode = off.
CREATE TABLE segments_v3 (
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

INSERT INTO segments_v3 (id, task_id, chunk_idx, seq, start_ms, end_ms, text, speaker_id)
SELECT id, task_id, chunk_idx, seq, start_ms, end_ms, text, NULL FROM segments;

DROP TABLE segments;

ALTER TABLE segments_v3 RENAME TO segments;
