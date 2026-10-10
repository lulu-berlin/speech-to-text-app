-- Roadmap item 22: speakers folded into another speaker of the same task, one row per merged speaker.
-- No segment row is rewritten: SegmentRepository.findDiarized reads a merged_id's segments as into_id's
-- and leaves merged speakers out of the task's effective speakers, so deleting a task's rows undoes every
-- merge. The application keeps the mapping flat: an into_id is never itself a merged_id of the same task.
CREATE TABLE speaker_merge (
    task_id   INTEGER NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    merged_id INTEGER NOT NULL,
    into_id   INTEGER NOT NULL,
    PRIMARY KEY (task_id, merged_id)
);
