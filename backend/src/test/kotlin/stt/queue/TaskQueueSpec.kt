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

        given("a running queue with one queued task") {
            `when`("the queue is paused and then resumed") {
                then("the paused flag toggles false to true and back, and the tasks are unchanged") {
                    val queue = queuedQueue(1L)
                    queue.paused.shouldBe(false)
                    queue.pause().shouldBe(TaskQueue(listOf(1L to QUEUED), paused = true))
                    queue.pause().resume().shouldBe(queue)
                }
            }
        }

        given("a paused queue of two queued tasks") {
            `when`("the tasks are reordered") {
                then("the new order is applied and the queue stays paused") {
                    val queue = queuedQueue(1L, 2L).pause()
                    queue.reorder(listOf(2L, 1L)).shouldBe(
                        ReorderOutcome.Accepted(TaskQueue(listOf(2L to QUEUED, 1L to QUEUED), paused = true)),
                    )
                }
            }
        }

        given("a paused queue and a running queue, each with one queued task") {
            `when`("the paused queue is paused again and the running queue is resumed again") {
                then("neither queue changes") {
                    val paused = TaskQueue(listOf(1L to QUEUED), paused = true)
                    val running = TaskQueue(listOf(1L to QUEUED), paused = false)
                    paused.pause().shouldBe(TaskQueue(listOf(1L to QUEUED), paused = true))
                    running.resume().shouldBe(TaskQueue(listOf(1L to QUEUED), paused = false))
                }
            }
        }

        given("a queue with task 1 RUNNING and task 2 QUEUED") {
            `when`("task 2 is started, then task 1 is paused and task 2 is started again") {
                then("the first start is rejected because another task is running; the second is accepted") {
                    val queue = TaskQueue(listOf(1L to RUNNING, 2L to QUEUED))
                    queue.transition(2L, RUNNING).shouldBe(
                        QueueTransitionOutcome.Rejected(QueueTransitionRejection.AnotherTaskRunning),
                    )
                    val idle = TaskQueue(listOf(1L to PAUSED, 2L to QUEUED))
                    queue.transition(1L, PAUSED).shouldBe(QueueTransitionOutcome.Accepted(idle))
                    idle.transition(2L, RUNNING).shouldBe(
                        QueueTransitionOutcome.Accepted(TaskQueue(listOf(1L to PAUSED, 2L to RUNNING))),
                    )
                }
            }
        }
    })
