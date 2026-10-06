package stt.audio

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

private val TARGET_MS = minutes(5)
private val CAP_MS = minutes(6)

private fun minutes(n: Int): Long = n * 60_000L

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
    })
