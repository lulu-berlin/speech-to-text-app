package stt.queue

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.queue.TaskStatus.CANCELED
import stt.queue.TaskStatus.COMPLETED
import stt.queue.TaskStatus.FAILED
import stt.queue.TaskStatus.PAUSED
import stt.queue.TaskStatus.QUEUED
import stt.queue.TaskStatus.RUNNING

class TaskStatusSpec :
    BehaviorSpec({

        given("each source status and its allowed targets from the spec's transition table") {
            `when`("every allowed transition is applied") {
                then("it is accepted and yields exactly the target status") {
                    val allowed =
                        mapOf(
                            QUEUED to listOf(RUNNING, PAUSED, CANCELED),
                            RUNNING to listOf(PAUSED, COMPLETED, FAILED, CANCELED),
                            PAUSED to listOf(QUEUED, CANCELED),
                        )
                    for ((from, targets) in allowed) {
                        for (to in targets) {
                            from.transition(to).shouldBe(TransitionOutcome.Accepted(to))
                        }
                    }
                }
            }
        }

        given("every status pair from a six-by-six sweep, including all pairs out of a terminal status") {
            `when`("the pair is not in the spec's allowed table") {
                then("the transition is rejected with that exact pair") {
                    val allowed =
                        setOf(
                            QUEUED to RUNNING,
                            QUEUED to PAUSED,
                            QUEUED to CANCELED,
                            RUNNING to PAUSED,
                            RUNNING to COMPLETED,
                            RUNNING to FAILED,
                            RUNNING to CANCELED,
                            PAUSED to QUEUED,
                            PAUSED to CANCELED,
                        )
                    for (from in TaskStatus.entries) {
                        for (to in TaskStatus.entries) {
                            if ((from to to) !in allowed) {
                                from.transition(to).shouldBe(TransitionOutcome.Rejected(from, to))
                            }
                        }
                    }
                }
            }
        }
    })
