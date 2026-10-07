-- Roadmap item 18 (ADR 0024): the original upload is no longer stored in the database
-- (it stays in $STT_DATA_DIR/work/<task-id>/source until chunking); chunks are stored as FLAC.
DROP TABLE audio_parts;

-- One FLAC (16-bit, 16 kHz mono) per planned chunk, one BLOB per row; it goes with its chunk,
-- and so with its task. Plan queries read only chunks, never this table.
CREATE TABLE chunk_audio (
    task_id INTEGER NOT NULL,
    idx     INTEGER NOT NULL,
    flac    BLOB    NOT NULL,
    PRIMARY KEY (task_id, idx),
    FOREIGN KEY (task_id, idx) REFERENCES chunks (task_id, idx) ON DELETE CASCADE
);
