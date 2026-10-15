-- Roadmap item 44: the canonical clip, one deconstructed 16 kHz mono FLAC per task. The FLAC header
-- (fLaC + metadata) lives in audio_header; the frames live in audio_second, four per row of exactly 250 ms
-- (declared by audio_second.frames), so row k covers [k·1000, (k+1)·1000) ms and the header plus the rows in
-- order is the encoded file byte for byte. The app's own encode fixes the frame size to 4000 samples, and the
-- splitter rejects anything that is not fixed-4000 frames, so the positions are arithmetic, not stored.
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