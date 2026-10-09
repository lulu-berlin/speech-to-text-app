package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.db.SpeakersMode
import stt.transcribe.WhisperSegment

class ProcessingEngineDiarizationSpec :
    BehaviorSpec({
        given("two queued tasks, one with speech and one without") {
            `when`("the engine runs them") {
                then("the one with speech is diarized and the one without segments completes without diarization") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val speech =
                            enqueueTask(
                                fixture.tasks,
                                fixture.workDirectories,
                                "speech.wav",
                                queuePosition = 0,
                                createdAt = 1,
                                speakersMode = SpeakersMode.AUTO,
                            )
                        val silence =
                            enqueueTask(
                                fixture.tasks,
                                fixture.workDirectories,
                                "silence.wav",
                                queuePosition = 1,
                                createdAt = 2,
                                speakersMode = SpeakersMode.AUTO,
                            )
                        fixture.transcriber.segmentsByTask =
                            mapOf(speech to listOf(WhisperSegment(startMs = 0, endMs = 1_000, text = " hello ")))

                        fixture.engine.start()

                        fixture.completed.receive() shouldBe speech
                        fixture.completed.receive() shouldBe silence

                        fixture.diarizer.diarized shouldBe listOf(speech)
                    }
                }
            }
        }
    })
