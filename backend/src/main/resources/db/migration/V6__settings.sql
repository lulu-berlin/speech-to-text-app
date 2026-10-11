-- Roadmap item 32: the app settings — the device and thread count for the next task the engine starts
-- (STT_DEVICE / STT_THREADS are gone) and the upload form's defaults (language, speakers mode, max speakers).
-- Single-row table like queue_state: id is always 1 (CHECK + PRIMARY KEY allow exactly one row), and the row
-- is seeded with the settled defaults by this migration. The device is stored in its wire form: auto, cpu,
-- or gpu:<index>.
CREATE TABLE settings (
    id               INTEGER NOT NULL,
    device           TEXT    NOT NULL,
    threads          INTEGER,
    default_language TEXT    NOT NULL,
    speakers_mode    TEXT    NOT NULL,
    speakers_count   INTEGER,
    max_speakers     INTEGER NOT NULL,
    PRIMARY KEY (id),
    CHECK (id = 1),
    CHECK (device IN ('auto', 'cpu') OR device LIKE 'gpu:%'),
    CHECK (threads IS NULL OR threads >= 1),
    CHECK (speakers_mode IN ('auto', 'off', 'fixed')),
    CHECK (speakers_count IS NULL OR speakers_count BETWEEN 1 AND 20),
    CHECK (max_speakers BETWEEN 1 AND 20)
);

INSERT INTO settings (id, device, threads, default_language, speakers_mode, speakers_count, max_speakers)
VALUES (1, 'auto', NULL, 'auto', 'auto', NULL, 8);