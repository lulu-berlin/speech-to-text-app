package stt.queue

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.queue.TaskStatus.PAUSED
import stt.queue.TaskStatus.QUEUED
import stt.queue.TaskStatus.RUNNING

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

        given("a queue of four non-terminal tasks in mixed statuses") {
            `when`("reordered with exactly the same ids in reverse order") {
                then("the new order is applied and every task keeps its status") {
                    val queue =
                        TaskQueue(
                            listOf(
                                1L to QUEUED,
                                2L to RUNNING,
                                3L to PAUSED,
                                4L to QUEUED,
                            ),
                        )
                    queue.reorder(listOf(4L, 3L, 2L, 1L)).shouldBe(
                        ReorderOutcome.Accepted(
                            TaskQueue(
                                listOf(
                                    4L to QUEUED,
                                    3L to PAUSED,
                                    2L to RUNNING,
                                    1L to QUEUED,
                                ),
                            ),
                        ),
                    )
                }
            }
        }

        given("a queue of three queued tasks") {
            `when`("reordered with a list that omits one of their ids") {
                then("the reorder is rejected") {
                    val queue = queuedQueue(1L, 2L, 3L)
                    queue.reorder(listOf(1L, 3L)).shouldBe(ReorderOutcome.Rejected)
                }
            }
        }

        given("a queue of three queued tasks") {
            `when`("reordered with a list that adds an id not in the queue (unknown or an already finished task)") {
                then("the reorder is rejected") {
                    val queue = queuedQueue(1L, 2L, 3L)
                    queue.reorder(listOf(1L, 2L, 3L, 9L)).shouldBe(ReorderOutcome.Rejected)
                }
            }
        }

        given("a queue of three queued tasks") {
            `when`("reordered with a list that repeats one id instead of naming every task once") {
                then("the reorder is rejected") {
                    val queue = queuedQueue(1L, 2L, 3L)
                    queue.reorder(listOf(1L, 2L, 2L)).shouldBe(ReorderOutcome.Rejected)
                }
            }
        }
    })
