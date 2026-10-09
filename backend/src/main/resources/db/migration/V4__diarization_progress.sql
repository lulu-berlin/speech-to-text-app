-- Roadmap item 11: the diarization bar (item 12's contract). Two stages of the DIARIZING phase, each
-- with progress 0-1 within the stage. diarization_stage is null outside the DIARIZING phase and on
-- COMPLETED; diarization_progress is 0 from the phase's start, 1 once the speakers are stored, and null
-- when diarization never applies (speakers off, or no segments).
ALTER TABLE tasks ADD COLUMN diarization_stage TEXT CHECK (diarization_stage IN ('EMBEDDING', 'CLUSTERING'));
ALTER TABLE tasks ADD COLUMN diarization_progress REAL;
