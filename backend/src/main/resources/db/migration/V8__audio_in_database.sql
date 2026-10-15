-- Roadmap item 44: an upload's original bytes are written to the database as they arrive, in windows of at most
-- 1 MiB, so no spool file exists anywhere. An original is staged (task_id NULL) while its upload is received and
-- attached to its task in the transaction that creates the task; a rejected or failed upload deletes it again.
CREATE TABLE original_audio (
    id      INTEGER PRIMARY KEY,
    task_id INTEGER UNIQUE REFERENCES tasks (id) ON DELETE CASCADE
);

-- The original's bytes in upload order: seq 0, 1, 2, … ; every window but the last is full.
CREATE TABLE original_audio_part (
    original_id INTEGER NOT NULL REFERENCES original_audio (id) ON DELETE CASCADE,
    seq         INTEGER NOT NULL CHECK (seq >= 0),
    bytes       BLOB    NOT NULL,
    PRIMARY KEY (original_id, seq)
);
