package stt.transcribe

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.db.Chunk
import stt.db.ChunkStatus

class TaskProgressSpec :
    BehaviorSpec({
        given("a 300 s DONE chunk, a 200 s chunk running at half, and a 500 s PENDING chunk") {
            `when`("task progress is computed") {
                then("it is weighted by chunk duration across finished and running chunks") {
                    val chunks =
                        listOf(
                            Chunk(idx = 0, startMs = 0, endMs = 300_000, status = ChunkStatus.DONE),
                            Chunk(idx = 1, startMs = 300_000, endMs = 500_000, status = ChunkStatus.PENDING),
                            Chunk(idx = 2, startMs = 500_000, endMs = 1_000_000, status = ChunkStatus.PENDING),
                        )

                    // (300 000 + 200 000 × 0.5) / 1 000 000 = 0.4; a per-chunk average would give (1 + 0.5 + 0) / 3 = 0.5.
                    taskProgress(chunks, runningIdx = 1, runningFraction = 0.5).shouldBe(0.4)
                }
            }
        }

        given("a chunk of zero total duration, running at half") {
            `when`("task progress is computed") {
                then("it reports 0, not NaN") {
                    // A decoded clip of 1–15 samples is 0 ms long (AudioDecoder rejects only 0 samples).
                    val chunks = listOf(Chunk(idx = 0, startMs = 0, endMs = 0, status = ChunkStatus.PENDING))

                    taskProgress(chunks, runningIdx = 0, runningFraction = 0.5).shouldBe(0.0)
                }
            }
        }
    })
