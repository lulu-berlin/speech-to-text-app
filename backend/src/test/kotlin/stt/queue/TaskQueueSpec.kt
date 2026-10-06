package stt.queue

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.queue.TaskStatus.PAUSED
import stt.queue.TaskStatus.QUEUED

class TaskQueueSpec :
    BehaviorSpec({

        given("a queue holding a PAUSED task between two queued tasks") {
            `when`("the tasks are reordered so the paused task moves") {
                then("the new order is applied and the paused task keeps its slot and its status") {
                    val queue =
                        TaskQueue(
                            listOf(
                                1L to QUEUED,
                                2L to PAUSED,
                                3L to QUEUED,
                            ),
                        )
                    queue.reorder(listOf(1L, 3L, 2L)).shouldBe(
                        ReorderOutcome.Accepted(
                            TaskQueue(
                                listOf(
                                    1L to QUEUED,
                                    3L to QUEUED,
                                    2L to PAUSED,
                                ),
                            ),
                        ),
                    )
                }
            }
        }
    })
