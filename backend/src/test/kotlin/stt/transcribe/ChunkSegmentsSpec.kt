package stt.transcribe

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.db.Segment

class ChunkSegmentsSpec :
    BehaviorSpec({
        given("whisper segments of chunk 2, which starts 300 000 ms into the original file") {
            `when`("they are turned into the chunk's segments") {
                then("segment times are shifted by the chunk start offset; seq numbers them from 0 in whisper order") {
                    val whisperSegments =
                        listOf(
                            WhisperSegment(startMs = 0, endMs = 7_940, text = " And so my fellow Americans"),
                            WhisperSegment(startMs = 7_940, endMs = 10_760, text = " ask what you can do"),
                        )

                    chunkSegments(chunkIdx = 2, chunkStartMs = 300_000, whisperSegments).shouldBe(
                        listOf(
                            Segment(
                                chunkIdx = 2,
                                seq = 0,
                                startMs = 300_000, // 300 000 + 0
                                endMs = 307_940, // 300 000 + 7 940
                                text = " And so my fellow Americans",
                                speakerId = null,
                            ),
                            Segment(
                                chunkIdx = 2,
                                seq = 1,
                                startMs = 307_940, // 300 000 + 7 940
                                endMs = 310_760, // 300 000 + 10 760
                                text = " ask what you can do",
                                speakerId = null,
                            ),
                        ),
                    )
                }
            }
        }
    })
