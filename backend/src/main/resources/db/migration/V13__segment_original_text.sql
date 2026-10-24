-- Item 52 / ADR 0060: a segment keeps whisper's original text, so Revert can restore it.
-- Rows that predate the column are backfilled with their current text: the original was
-- overwritten by any edit made before this migration.
ALTER TABLE segments ADD COLUMN original_text TEXT;
UPDATE segments SET original_text = text;
