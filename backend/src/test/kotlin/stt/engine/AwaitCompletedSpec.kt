package stt.engine

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import stt.db.SpeakersMode
import stt.transcribe.WhisperSegment
import kotlin.time.Duration.Companion.milliseconds

/**
 * The completion wait the atomic-transition specs use (roadmap item 58): its bound is a safety net, so a
 * task that never completes fails naming itself instead of passing or timing out anonymously.
 */
class AwaitCompletedSpec :
    BehaviorSpec({
        given("a task that never completes, after an earlier task that did") {
            `when`("the wait's safety bound passes") {
                then("it fails naming only the missing task") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val first = enqueueTask(fixture.tasks, "first.wav", queuePosition = 0, createdAt = 1)
                        // The next task has speakers, so it reaches DIARIZING, where the fake stops it.
                        val next =
                            enqueueTask(
                                fixture.tasks,
                                "next.wav",
                                queuePosition = 1,
                                createdAt = 2,
                                speakersMode = SpeakersMode.AUTO,
                            )
                        fixture.transcriber.segmentsByTask =
                            mapOf(next to listOf(WhisperSegment(startMs = 0, endMs = 1_000, text = " hi ")))
                        fixture.diarizer.hold = CompletableDeferred() // never released
                        fixture.engine.start()
                        // The wait must run against a task that is really stuck, not one still working.
                        fixture.diarizer.entered.receive() shouldBe next

                        val error =
                            shouldThrow<AssertionError> {
                                awaitCompleted(fixture.completed, setOf(first, next), bound = 500.milliseconds)
                            }
                        // The earlier task's COMPLETED was received; only the stuck one is named missing.
                        error.message shouldContain "task(s) [$next]"
                        error.message shouldContain "completed ids received: [$first]"
                    }
                }
            }
        }
    })
