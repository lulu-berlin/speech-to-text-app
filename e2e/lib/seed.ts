// Seeds the service's database with a finished task (roadmap item 76, Decided 2026-10-28): the
// fixture model cannot transcribe right-to-left speech, so a spec that needs such a transcript
// inserts the completed task's rows while the service is stopped and reads them once it starts.
import { createHash } from 'node:crypto';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';

export interface SeededTurn {
  speakerId: number;
  startMs: number;
  endMs: number;
  text: string;
}

/**
 * Inserts a COMPLETED task named [filename] into `stt.db` in [dataDir] — its upload's `audio` row
 * (every task has one; no audio bytes are stored), its speakers (by position: the first is speaker
 * 0) and one segment per turn — and returns its id. The service must be stopped: it reads the rows
 * when it starts.
 */
export function seedCompletedTask(
  dataDir: string,
  task: { filename: string; language: string; speakers: string[]; turns: SeededTurn[] },
): number {
  const database = new DatabaseSync(join(dataDir, 'stt.db'));
  try {
    const now = Date.now();
    const durationMs = Math.max(...task.turns.map((turn) => turn.endMs));
    const { lastInsertRowid } = database
      .prepare(
        `INSERT INTO tasks (original_filename, content_type, duration_ms, status, language,
           speakers_mode, progress, created_at, started_at, finished_at)
         VALUES (?, 'audio/wav', ?, 'COMPLETED', ?, 'auto', 1, ?, ?, ?)`,
      )
      .run(task.filename, durationMs, task.language, now, now, now);
    const taskId = Number(lastInsertRowid);
    // The upload's record: the task list joins it. Its hash is unique per seeded task, so a later
    // upload is never taken for a duplicate of it.
    database
      .prepare('INSERT INTO audio (task_id, size_bytes, sha256) VALUES (?, 0, ?)')
      .run(taskId, createHash('sha256').update(`seeded:${task.filename}:${now}`).digest('hex'));
    const speaker = database.prepare(
      'INSERT INTO speakers (task_id, speaker_id, speaker) VALUES (?, ?, ?)',
    );
    task.speakers.forEach((name, speakerId) => speaker.run(taskId, speakerId, name));
    const segment = database.prepare(
      `INSERT INTO segments (task_id, chunk_idx, seq, start_ms, end_ms, text, original_text, speaker_id)
       VALUES (?, 0, ?, ?, ?, ?, ?, ?)`,
    );
    task.turns.forEach((turn, seq) =>
      segment.run(taskId, seq, turn.startMs, turn.endMs, turn.text, turn.text, turn.speakerId),
    );
    return taskId;
  } finally {
    database.close();
  }
}
