package stt.queue

import stt.queue.TaskStatus.QUEUED

/** A queue whose tasks are all QUEUED, in the given id order. */
internal fun queuedQueue(vararg ids: Long): TaskQueue = TaskQueue(ids.map { id -> id to QUEUED })
