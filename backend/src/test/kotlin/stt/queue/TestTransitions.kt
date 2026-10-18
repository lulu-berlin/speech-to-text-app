package stt.queue

import stt.queue.TaskStatus.CANCELED
import stt.queue.TaskStatus.COMPLETED
import stt.queue.TaskStatus.FAILED
import stt.queue.TaskStatus.PAUSED
import stt.queue.TaskStatus.QUEUED
import stt.queue.TaskStatus.RUNNING

/**
 * The spec's per-task allowed transitions, hand-transcribed from the transition
 * table (roadmap item 04, as amended 2026-10-06, by item 11 for retry, and by item 42 for
 * a task whose process is gone or whose start itself fails, ADR 0057/0058).
 * Shared by the acceptance and rejection sweeps in TaskStatusSpec.
 */
internal val allowedTransitions: Set<Pair<TaskStatus, TaskStatus>> =
    setOf(
        QUEUED to RUNNING,
        QUEUED to PAUSED,
        QUEUED to FAILED,
        QUEUED to CANCELED,
        RUNNING to QUEUED,
        RUNNING to PAUSED,
        RUNNING to COMPLETED,
        RUNNING to FAILED,
        RUNNING to CANCELED,
        PAUSED to QUEUED,
        PAUSED to CANCELED,
        FAILED to QUEUED,
        CANCELED to QUEUED,
    )
