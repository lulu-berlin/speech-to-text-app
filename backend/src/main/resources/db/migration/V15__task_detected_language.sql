-- Item 74: whisper's detected language per task, so the transcript is marked with it.
-- Nullable: rows that predate the column, and runs that detected nothing, fall back to the requested language.
ALTER TABLE tasks ADD COLUMN detected_language TEXT;
