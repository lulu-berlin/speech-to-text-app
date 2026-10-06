package stt.queue

import io.kotest.assertions.fail
import stt.queue.TaskStatus.QUEUED

/** A queue built through the validating factory; fails the test if the factory rejects the entries. */
internal fun queueOf(
    vararg entries: QueueEntry,
    paused: Boolean = false,
): TaskQueue =
    when (val outcome = TaskQueue.of(entries.toList(), paused)) {
        is QueueCreationOutcome.Accepted -> outcome.queue
        is QueueCreationOutcome.Rejected -> fail("test queue rejected by the factory: ${outcome.reason}")
    }

/** A queue whose tasks are all QUEUED, in the given id order. */
internal fun queuedQueue(vararg ids: Long): TaskQueue = queueOf(*ids.map { id -> QueueEntry(id, QUEUED) }.toTypedArray())
