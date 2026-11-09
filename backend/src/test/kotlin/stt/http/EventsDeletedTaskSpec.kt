package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableSharedFlow
import stt.db.StoredTask
import stt.db.queuedTask
import stt.engine.Event
import stt.queue.TaskStatus

/**
 * Item 95: a `task-updated` buffered for a task that was deleted before its frame was written is skipped — the
 * following `task-deleted` still reaches the client and the stream stays open, instead of the `!!` ending it.
 */
class EventsDeletedTaskSpec :
    BehaviorSpec({
        given("a subscriber whose queued task-updated belongs to a task already deleted") {
            `when`("its frames are written") {
                then("the update is skipped, task-deleted follows, and the stream stays open") {
                    val dataDir = tempdir("stt-events-deleted").toPath()
                    val events = MutableSharedFlow<Pair<Long, Event>>(replay = 4, extraBufferCapacity = 4)
                    val gone = StoredTask(1, queuedTask("gone.wav", createdAt = 1).copy(status = TaskStatus.COMPLETED))

                    SseServer(
                        {
                            sttApp(
                                stt.config.testConfig(dataDir),
                                eventStream =
                                    object : EventStream {
                                        override val events = events
                                    },
                            )
                        },
                    ).use { server ->
                        val reader = server.connectEvents()
                        reader.readEvent().first shouldBe "snapshot"

                        events.emit(0L to Event.TaskUpdated(gone))
                        events.emit(1L to Event.TaskDeleted(gone.id))
                        events.emit(2L to Event.QueueUpdated(paused = false, taskIds = emptyList()))

                        // The deleted task's update never becomes a frame; the deletion and later events still arrive.
                        reader.readEvent().first shouldBe "task-deleted"
                        reader.readEvent().first shouldBe "queue-updated"
                    }
                }
            }
        }
    })
