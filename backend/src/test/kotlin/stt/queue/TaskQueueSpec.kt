package stt.queue

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import stt.queue.ReorderRejection.DuplicateIds
import stt.queue.ReorderRejection.MissingIds
import stt.queue.ReorderRejection.UnknownIds
import stt.queue.TaskStatus.CANCELED
import stt.queue.TaskStatus.COMPLETED
import stt.queue.TaskStatus.FAILED
import stt.queue.TaskStatus.PAUSED
import stt.queue.TaskStatus.QUEUED
import stt.queue.TaskStatus.RUNNING
import stt.queue.QueueCreationRejection as Creation

class TaskQueueSpec :
    BehaviorSpec({

        given("a queue holding a PAUSED task between two queued tasks") {
            `when`("the tasks are reordered so the paused task moves") {
                then("the new order is applied and the paused task keeps its slot and its status") {
                    val queue = queueOf(QueueEntry(1L, QUEUED), QueueEntry(2L, PAUSED), QueueEntry(3L, QUEUED))
                    queue.reorder(listOf(1L, 3L, 2L)).shouldBe(
                        ReorderOutcome.Accepted(queueOf(QueueEntry(1L, QUEUED), QueueEntry(3L, QUEUED), QueueEntry(2L, PAUSED))),
                    )
                }
            }
        }

        given("a queue of four non-terminal tasks in mixed statuses") {
            `when`("reordered with exactly the same ids in reverse order") {
                then("the new order is applied and every task keeps its status") {
                    val queue =
                        queueOf(QueueEntry(1L, QUEUED), QueueEntry(2L, RUNNING), QueueEntry(3L, PAUSED), QueueEntry(4L, QUEUED))
                    queue.reorder(listOf(4L, 3L, 2L, 1L)).shouldBe(
                        ReorderOutcome.Accepted(
                            queueOf(QueueEntry(4L, QUEUED), QueueEntry(3L, PAUSED), QueueEntry(2L, RUNNING), QueueEntry(1L, QUEUED)),
                        ),
                    )
                }
            }
        }

        given("a queue of three queued tasks") {
            `when`("reordered with a list that omits one of their ids") {
                then("the reorder is rejected, naming the missing id") {
                    val queue = queuedQueue(1L, 2L, 3L)
                    queue.reorder(listOf(1L, 3L)).shouldBe(ReorderOutcome.Rejected(MissingIds(setOf(2L))))
                }
            }
        }

        given("a queue of three queued tasks") {
            `when`("reordered with a list that adds an id not in the queue (unknown or an already finished task)") {
                then("the reorder is rejected, naming the unknown id") {
                    val queue = queuedQueue(1L, 2L, 3L)
                    queue.reorder(listOf(1L, 2L, 3L, 9L)).shouldBe(ReorderOutcome.Rejected(UnknownIds(setOf(9L))))
                }
            }
        }

        given("a queue of three queued tasks") {
            `when`("reordered with a list that repeats one id instead of naming every task once") {
                then("the reorder is rejected, naming the duplicated id") {
                    val queue = queuedQueue(1L, 2L, 3L)
                    queue.reorder(listOf(1L, 2L, 2L)).shouldBe(ReorderOutcome.Rejected(DuplicateIds(setOf(2L))))
                }
            }
        }

        given("a queue of three queued tasks with ids 1, 2 and 3") {
            `when`("reordered with a list that repeats id 1 while still naming every task") {
                then("the reorder is rejected, naming the duplicated id") {
                    val queue = queuedQueue(1L, 2L, 3L)
                    queue.reorder(listOf(1L, 1L, 2L, 3L)).shouldBe(ReorderOutcome.Rejected(DuplicateIds(setOf(1L))))
                }
            }
        }

        given("a queue of three queued tasks with ids 1, 2 and 3") {
            `when`("reordered with a list for which several rejection reasons hold at once") {
                then("the first of duplicate > unknown > missing is reported") {
                    val queue = queuedQueue(1L, 2L, 3L)
                    // [1, 1, 9]: 1 duplicated, 9 unknown, 2 and 3 missing → duplicate wins.
                    queue.reorder(listOf(1L, 1L, 9L)).shouldBe(ReorderOutcome.Rejected(DuplicateIds(setOf(1L))))
                    // [1, 2, 9]: 9 unknown, 3 missing → unknown wins.
                    queue.reorder(listOf(1L, 2L, 9L)).shouldBe(ReorderOutcome.Rejected(UnknownIds(setOf(9L))))
                }
            }
        }

        given("a running queue with one queued task") {
            `when`("the queue is paused and then resumed") {
                then("the paused flag toggles false to true and back, and the tasks are unchanged") {
                    val queue = queuedQueue(1L)
                    queue.paused.shouldBe(false)
                    queue.pause().shouldBe(queueOf(QueueEntry(1L, QUEUED), paused = true))
                    queue.pause().resume().shouldBe(queue)
                }
            }
        }

        given("a paused queue of two queued tasks") {
            `when`("the tasks are reordered") {
                then("the new order is applied and the queue stays paused") {
                    val queue = queuedQueue(1L, 2L).pause()
                    queue.reorder(listOf(2L, 1L)).shouldBe(
                        ReorderOutcome.Accepted(
                            queueOf(QueueEntry(2L, QUEUED), QueueEntry(1L, QUEUED), paused = true),
                        ),
                    )
                }
            }
        }

        given("a paused queue and a running queue, each with one queued task") {
            `when`("the paused queue is paused again and the running queue is resumed again") {
                then("neither queue changes") {
                    val paused = queueOf(QueueEntry(1L, QUEUED), paused = true)
                    val running = queueOf(QueueEntry(1L, QUEUED), paused = false)
                    paused.pause().shouldBe(queueOf(QueueEntry(1L, QUEUED), paused = true))
                    running.resume().shouldBe(queueOf(QueueEntry(1L, QUEUED), paused = false))
                }
            }
        }

        given("a queue with task 1 RUNNING and task 2 QUEUED") {
            `when`("task 2 is started, then task 1 is paused and task 2 is started again") {
                then("the first start is rejected because another task is running; the second is accepted") {
                    val queue = queueOf(QueueEntry(1L, RUNNING), QueueEntry(2L, QUEUED))
                    queue.transition(2L, RUNNING).shouldBe(
                        QueueTransitionOutcome.Rejected(QueueTransitionRejection.AnotherTaskRunning),
                    )
                    val idle = queueOf(QueueEntry(1L, PAUSED), QueueEntry(2L, QUEUED))
                    queue.transition(1L, PAUSED).shouldBe(QueueTransitionOutcome.Accepted(idle))
                    idle.transition(2L, RUNNING).shouldBe(
                        QueueTransitionOutcome.Accepted(queueOf(QueueEntry(1L, PAUSED), QueueEntry(2L, RUNNING))),
                    )
                }
            }
        }

        given("a queue where RUNNING task 1 is followed by QUEUED task 2") {
            `when`("task 1 completes") {
                then("the change is accepted and task 1 leaves the queue") {
                    val queue = queueOf(QueueEntry(1L, RUNNING), QueueEntry(2L, QUEUED))
                    queue.transition(1L, COMPLETED).shouldBe(
                        QueueTransitionOutcome.Accepted(queueOf(QueueEntry(2L, QUEUED))),
                    )
                }
            }
        }

        given("a queue of two queued tasks with ids 1 and 2") {
            `when`("a status change is requested for task 9, which is not in the queue") {
                then("it is rejected as an unknown task and nothing is thrown") {
                    val queue = queuedQueue(1L, 2L)
                    queue.transition(9L, RUNNING).shouldBe(
                        QueueTransitionOutcome.Rejected(QueueTransitionRejection.UnknownTask(9L)),
                    )
                }
            }
        }

        given("a queue of QUEUED task 1 and PAUSED task 2, with no task running") {
            `when`("task 1 is completed, or (separately) task 2 is started, which the per-task table forbids") {
                then("each change is rejected as an illegal transition naming both statuses, and the queue is unchanged") {
                    val queue = queueOf(QueueEntry(1L, QUEUED), QueueEntry(2L, PAUSED))
                    queue.transition(1L, COMPLETED).shouldBe(
                        QueueTransitionOutcome.Rejected(QueueTransitionRejection.IllegalTransition(QUEUED, COMPLETED)),
                    )
                    queue.transition(2L, RUNNING).shouldBe(
                        QueueTransitionOutcome.Rejected(QueueTransitionRejection.IllegalTransition(PAUSED, RUNNING)),
                    )
                    queue.shouldBe(queueOf(QueueEntry(1L, QUEUED), QueueEntry(2L, PAUSED)))
                }
            }
        }

        given("a queue where RUNNING task 1 is followed by QUEUED task 2, as before task 1 ends") {
            `when`("task 1 fails, or (separately) task 2 is canceled") {
                then("each change is accepted and the failed or canceled task leaves the queue") {
                    val queue = queueOf(QueueEntry(1L, RUNNING), QueueEntry(2L, QUEUED))
                    queue.transition(1L, FAILED).shouldBe(
                        QueueTransitionOutcome.Accepted(queueOf(QueueEntry(2L, QUEUED))),
                    )
                    queue.transition(2L, CANCELED).shouldBe(
                        QueueTransitionOutcome.Accepted(queueOf(QueueEntry(1L, RUNNING))),
                    )
                }
            }
        }

        given("sets of queue entries as item 11 would read them from the database at boot") {
            `when`("a queue is built from each set through the factory") {
                then("an invalid set is rejected with the first of duplicate > terminal > multiple running; a valid set is kept as given") {
                    TaskQueue.of(listOf(QueueEntry(1L, QUEUED), QueueEntry(1L, PAUSED))).shouldBe(
                        QueueCreationOutcome.Rejected(Creation.DuplicateIds(setOf(1L))),
                    )
                    TaskQueue.of(listOf(QueueEntry(1L, QUEUED), QueueEntry(2L, COMPLETED), QueueEntry(3L, CANCELED))).shouldBe(
                        QueueCreationOutcome.Rejected(Creation.TerminalEntries(setOf(2L, 3L))),
                    )
                    TaskQueue.of(listOf(QueueEntry(1L, RUNNING), QueueEntry(2L, RUNNING), QueueEntry(3L, QUEUED))).shouldBe(
                        QueueCreationOutcome.Rejected(Creation.MultipleRunning(setOf(1L, 2L))),
                    )
                    // 1 duplicated, 1 terminal, 1 and 2 RUNNING → duplicate wins.
                    TaskQueue.of(listOf(QueueEntry(1L, RUNNING), QueueEntry(1L, COMPLETED), QueueEntry(2L, RUNNING))).shouldBe(
                        QueueCreationOutcome.Rejected(Creation.DuplicateIds(setOf(1L))),
                    )
                    // 3 terminal, 1 and 2 RUNNING → terminal wins.
                    TaskQueue.of(listOf(QueueEntry(1L, RUNNING), QueueEntry(2L, RUNNING), QueueEntry(3L, FAILED))).shouldBe(
                        QueueCreationOutcome.Rejected(Creation.TerminalEntries(setOf(3L))),
                    )

                    val valid = listOf(QueueEntry(1L, RUNNING), QueueEntry(2L, PAUSED), QueueEntry(3L, QUEUED))
                    val restored = TaskQueue.of(valid, paused = true).shouldBeInstanceOf<QueueCreationOutcome.Accepted>().queue
                    restored.entries.shouldBe(valid)
                    restored.paused.shouldBe(true)
                    val empty = TaskQueue.of(emptyList()).shouldBeInstanceOf<QueueCreationOutcome.Accepted>().queue
                    empty.entries.shouldBe(emptyList())
                    empty.paused.shouldBe(false)
                }
            }
        }
    })
