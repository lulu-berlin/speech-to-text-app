-- Roadmap item 88: the app's own language. One user preference shared by every client, kept apart from
-- default_language, which is the transcription language whisper receives. NULL means the app follows the system's
-- language when it is one of the shipped UI locales, and English otherwise.
ALTER TABLE settings ADD COLUMN app_language TEXT;
