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
    })
