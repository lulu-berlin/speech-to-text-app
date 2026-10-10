package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runCurrent
import stt.db.DiarizationStage
import stt.db.SpeakersMode
import stt.db.TaskPhase
import stt.queue.TaskStatus
import stt.transcribe.WhisperSegment
import kotlin.time.Duration.Companion.milliseconds

class ProcessingEngineEventsSpec :
    BehaviorSpec({
        given("a task running to completion and then deleted") {
            `when`("the engine processes it") {
                then("every status, phase and progress change is published after it is persisted, and the deletion last") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task =
                            enqueueTask(
                                fixture.tasks,
                                fixture.workDirectories,
                                "task.wav",
                                queuePosition = 0,
                                createdAt = 1,
                                speakersMode = SpeakersMode.AUTO,
                            )
                        fixture.transcriber.segmentsByTask =
                            mapOf(task to listOf(WhisperSegment(startMs = 0, endMs = 1_000, text = " hello ")))
                        fixture.transcriber.progressScript = listOf(0.5, 1.0)

                        fixture.engine.start()
                        fixture.completed.receive() shouldBe task
                        fixture.engine.deleteTask(task) shouldBe TaskCommandOutcome.Accepted
                        // Let the event collector run so the deletion event is in the channel before the drain.
                        runCurrent()

                        val events = drain(fixture.events)
                        val updates = events.filterIsInstance<Event.TaskUpdated>()
                        // Every status- and phase-changing write is published, with the phase it left in.
                        updates.map { it.task.task.phase } shouldBe
                            listOf(null, TaskPhase.CHUNKING, TaskPhase.TRANSCRIBING, TaskPhase.DIARIZING, null)
                        updates
                            .last()
                            .task.task.status shouldBe TaskStatus.COMPLETED
                        // Every transcription and diarization progress report is published, the diarization
                        // ones against the finished transcription value.
                        events.filterIsInstance<Event.TaskProgress>().map {
                            it.transcriptionProgress to it.diarizationStage to
                                it.diarizationProgress
                        } shouldBe
                            listOf(
                                0.5 to null to null,
                                1.0 to null to null,
                                1.0 to null to 0.0,
                                1.0 to DiarizationStage.EMBEDDING to 0.0,
                                1.0 to DiarizationStage.EMBEDDING to 0.5,
                                1.0 to DiarizationStage.EMBEDDING to 1.0,
                                1.0 to DiarizationStage.CLUSTERING to 0.0,
                                1.0 to DiarizationStage.CLUSTERING to 1.0,
                                1.0 to null to 1.0,
                            )
                        // The deletion is the one event after the row (and everything cascading) is gone.
                        events.filterIsInstance<Event.TaskDeleted>().map { it.id } shouldBe listOf(task)
                        // The deletion is followed only by the queue-updated (the task left the queue).
                        events.last().shouldBeInstanceOf<Event.QueueUpdated>()
                        fixture.tasks.find(task) shouldBe null
                    }
                }
            }
        }

        given("a queue with two tasks, exercised through pause, reorder, completion and deletion") {
            `when`("the queue changes") {
                then("a queue-updated is emitted after each membership change, matching the database") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val first = enqueueTask(fixture.tasks, fixture.workDirectories, "first.wav", queuePosition = 0, createdAt = 1)
                        val second = enqueueTask(fixture.tasks, fixture.workDirectories, "second.wav", queuePosition = 1, createdAt = 2)

                        // pause, reorder behind the worker's back, then resume: every membership or
                        // order change while the queue is paused is still reported.
                        fixture.engine.pauseQueue()
                        fixture.engine.start()
                        fixture.engine.reorderQueue(listOf(second, first))
                        fixture.engine.resumeQueue()
                        fixture.completed.receive() shouldBe second
                        fixture.completed.receive() shouldBe first
                        // Let the worker finish the last completion's queue event and the collector catch up.
                        pumpUntil(200.milliseconds) { false }

                        val updates = drain(fixture.events).filterIsInstance<Event.QueueUpdated>()
                        updates.map { it.taskIds } shouldBe
                            listOf(
                                listOf(first, second), // paused
                                listOf(second, first), // reordered, still paused
                                listOf(second, first), // resumed
                                listOf(first), // second completed and left
                                emptyList(), // first completed and left
                            )
                        updates.map { it.paused } shouldBe listOf(true, true, false, false, false)
                    }
                }
            }
        }
    })
