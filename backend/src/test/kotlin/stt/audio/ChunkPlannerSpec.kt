package stt.audio

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

private val TARGET_MS = minutes(5)
private val CAP_MS = minutes(6)

private fun minutes(n: Int): Long = n * 60_000L

private fun seconds(n: Int): Long = n * 1_000L

class ChunkPlannerSpec :
    BehaviorSpec({
        given("a file shorter than the target length") {
            `when`("the chunks are planned") {
                then("it becomes exactly one chunk covering the whole file") {
                    val durationMs = minutes(2)

                    val chunks = planChunks(durationMs, emptyList(), TARGET_MS, CAP_MS)

                    chunks.shouldBe(listOf(PlannedChunk(startMs = 0, endMs = minutes(2))))
                }
            }
        }

        given("audio with no silences") {
            `when`("the chunks are planned") {
                then("it is cut every target length") {
                    val durationMs = minutes(12)

                    val chunks = planChunks(durationMs, emptyList(), TARGET_MS, CAP_MS)

                    chunks.shouldBe(
                        listOf(
                            PlannedChunk(startMs = 0, endMs = minutes(5)),
                            PlannedChunk(startMs = minutes(5), endMs = minutes(10)),
                            PlannedChunk(startMs = minutes(10), endMs = minutes(12)),
                        ),
                    )
                }
            }
        }

        given("audio with many silences") {
            `when`("the chunks are planned") {
                then("each cut lands at the midpoint of the silence nearest the target") {
                    val durationMs = seconds(850)
                    val silences =
                        listOf(
                            Silence(startMs = seconds(250), endMs = seconds(252)), // midpoint 251 s
                            Silence(startMs = seconds(290), endMs = seconds(292)), // midpoint 291 s
                            Silence(startMs = seconds(305), endMs = seconds(309)), // midpoint 307 s: nearest to 300 s
                            Silence(startMs = seconds(595), endMs = seconds(597)), // midpoint 596 s
                            Silence(startMs = seconds(610), endMs = seconds(616)), // midpoint 613 s: nearest to 307 + 300 = 607 s
                        )

                    val chunks = planChunks(durationMs, silences, TARGET_MS, CAP_MS)

                    chunks.shouldBe(
                        listOf(
                            PlannedChunk(startMs = 0, endMs = seconds(307)),
                            PlannedChunk(startMs = seconds(307), endMs = seconds(613)),
                            PlannedChunk(startMs = seconds(613), endMs = seconds(850)),
                        ),
                    )
                }
            }
        }

        given("a silence beyond the cap") {
            `when`("the chunks are planned") {
                then("it is never chosen; the cut falls back to the target") {
                    val durationMs = seconds(590)
                    val silences = listOf(Silence(startMs = seconds(362), endMs = seconds(364))) // midpoint 363 s > cap 360 s

                    val chunks = planChunks(durationMs, silences, TARGET_MS, CAP_MS)

                    chunks.shouldBe(
                        listOf(
                            PlannedChunk(startMs = 0, endMs = seconds(300)),
                            PlannedChunk(startMs = seconds(300), endMs = seconds(590)),
                        ),
                    )
                }
            }
        }

        given("a silence at or before the current chunk start") {
            `when`("the chunks are planned") {
                then("it is never chosen") {
                    val durationMs = seconds(660)
                    val silences = listOf(Silence(startMs = seconds(290), endMs = seconds(292))) // midpoint 291 s: the first cut

                    val chunks = planChunks(durationMs, silences, TARGET_MS, CAP_MS)

                    chunks.shouldBe(
                        listOf(
                            PlannedChunk(startMs = 0, endMs = seconds(291)),
                            PlannedChunk(startMs = seconds(291), endMs = seconds(591)), // 291 + 300: no silence after 291 s
                            PlannedChunk(startMs = seconds(591), endMs = seconds(660)),
                        ),
                    )
                }
            }
        }
    })
