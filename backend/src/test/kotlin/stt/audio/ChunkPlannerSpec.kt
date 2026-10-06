package stt.audio

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

private const val TARGET_MS = 5 * 60 * 1000L
private const val CAP_MS = 6 * 60 * 1000L

class ChunkPlannerSpec :
    BehaviorSpec({
        given("a file shorter than the target length") {
            `when`("the chunks are planned") {
                then("it becomes exactly one chunk covering the whole file") {
                    val durationMs = 2 * 60 * 1000L

                    val chunks = planChunks(durationMs, emptyList(), TARGET_MS, CAP_MS)

                    chunks.shouldBe(listOf(PlannedChunk(startMs = 0, endMs = 120_000)))
                }
            }
        }
    })
