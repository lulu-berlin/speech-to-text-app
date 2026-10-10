package stt.http

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.flow.flow
import stt.engine.Event

/**
 * A subscriber that fell behind must be disconnected instead of silently missing events, and a reconnect
 * starts with a fresh snapshot (user decision 2026-10-10), which [EventsSpec] pins for the connection. This
 * spec pins the rule itself: [pumpEvents] stops the stream at the first gap in the event sequence — the gap
 * is exactly what a subscriber sees when the engine's `DROP_OLDEST` flow drops the events it could not keep
 * up with. (The DI override route collided with the app's own registration — `DuplicateDependencyException`,
 * logged 2026-10-10 — so the rule is tested at the pump, not through the request stack.)
 */
class EventsOverflowSpec :
    BehaviorSpec({

        fun recordInto(frames: MutableList<String>): suspend (Event) -> Unit =
            { event ->
                frames +=
                    when (event) {
                        is Event.QueueUpdated -> "queue-updated"
                        is Event.TaskUpdated -> "task-updated"
                        is Event.TaskProgress -> "task-progress"
                        is Event.TaskDeleted -> "task-deleted"
                        is Event.SpeakersRenamed -> "speakers-renamed"
                    }
            }

        given("a stream whose sequence shows dropped events") {
            `when`("it is pumped") {
                then("the frames before the gap are written and the pump stops") {
                    val frames = mutableListOf<String>()
                    shouldThrow<FellBehindException> {
                        pumpEvents(
                            flow {
                                // 0, 1, 2 delivered, then 100: as if 3..99 were dropped for this subscriber.
                                for (sequence in listOf(0L, 1L, 2L, 100L)) {
                                    emit(sequence to Event.QueueUpdated(paused = false, taskIds = emptyList()))
                                }
                            },
                            recordInto(frames),
                        )
                    }
                    frames shouldContainExactly listOf("queue-updated", "queue-updated", "queue-updated")
                }
            }
        }

        given("a contiguous stream") {
            `when`("it is pumped") {
                then("every frame is written and the pump runs to the end of the stream") {
                    val frames = mutableListOf<String>()
                    pumpEvents(
                        flow {
                            for (sequence in 0L..3L) {
                                emit(sequence to Event.QueueUpdated(paused = true, taskIds = emptyList()) as Event)
                            }
                        },
                        recordInto(frames),
                    )
                    frames shouldContainExactly listOf("queue-updated", "queue-updated", "queue-updated", "queue-updated")
                }
            }
        }
    })
