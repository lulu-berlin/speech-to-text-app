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
                        events.last().shouldBeInstanceOf<Event.TaskDeleted>()
                        fixture.tasks.find(task) shouldBe null
                    }
                }
            }
        }
    })
