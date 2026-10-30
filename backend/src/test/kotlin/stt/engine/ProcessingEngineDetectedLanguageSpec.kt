package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.http.toJson
import stt.transcribe.WhisperSegment

/**
 * Item 74, test-list item "An auto-uploaded task stores whisper's detected language, the API exposes it, and
 * its transcript marks that language; a task whose detection is empty falls back to the requested language":
 * the language whisper detects is stored with the task and is what the task JSON exposes; a task whose run
 * detects none keeps its requested language.
 */
class ProcessingEngineDetectedLanguageSpec :
    BehaviorSpec({
        given("an auto task whose whisper run detects Japanese, and a French task whose run detects none") {
            `when`("the engine completes both") {
                then("the first task's JSON carries the detected language and the second keeps the requested one") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val japanese =
                            enqueueTask(
                                fixture.tasks,
                                "japanese.wav",
                                queuePosition = 0,
                                createdAt = 1,
                                language = "auto",
                            )
                        val french =
                            enqueueTask(
                                fixture.tasks,
                                "french.wav",
                                queuePosition = 1,
                                createdAt = 2,
                                language = "fr",
                            )
                        fixture.transcriber.detectedLanguageByTask = mapOf(japanese to "ja")
                        fixture.transcriber.segmentsByTask =
                            mapOf(
                                japanese to listOf(WhisperSegment(startMs = 0, endMs = 1_000, text = "こんにちは")),
                                french to listOf(WhisperSegment(startMs = 0, endMs = 1_000, text = "bonjour")),
                            )

                        fixture.engine.start()
                        fixture.completed.receive() shouldBe japanese
                        fixture.completed.receive() shouldBe french

                        fixture.tasks
                            .details(japanese)!!
                            .toJson()
                            .language shouldBe "ja"
                        fixture.tasks
                            .details(french)!!
                            .toJson()
                            .language shouldBe "fr"
                    }
                }
            }
        }
    })
