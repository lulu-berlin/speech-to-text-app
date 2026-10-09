package stt.transcribe

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.db.Segment
import stt.db.SegmentRepository
import stt.db.queuedTask
import stt.db.taskRepository
import stt.db.withDatabase

class ResumePromptSpec :
    BehaviorSpec({
        given("a task whose chunks 0 and 1 have stored segments") {
            `when`("transcription resumes") {
                then("the prompt is rebuilt from the previous chunk's stored segments") {
                    withDatabase { db ->
                        val segments = SegmentRepository(db)
                        val taskId = taskRepository(db, dataDir).insert(queuedTask("talk.wav", createdAt = 1L))
                        segments.insertAll(
                            taskId,
                            listOf(
                                Segment(
                                    chunkIdx = 0,
                                    seq = 0,
                                    startMs = 0,
                                    endMs = 7_940,
                                    text = " And so my fellow Americans",
                                    speakerId = null,
                                ),
                                Segment(
                                    chunkIdx = 1,
                                    seq = 0,
                                    startMs = 300_000,
                                    endMs = 302_000,
                                    text = " ask what you can do",
                                    speakerId = null,
                                ),
                                Segment(
                                    chunkIdx = 1,
                                    seq = 1,
                                    startMs = 302_000,
                                    endMs = 304_000,
                                    text = " for your country.",
                                    speakerId = null,
                                ),
                            ),
                        )

                        // Resuming at chunk 2: only chunk 1's text, joined and trimmed.
                        resumePrompt(segments, taskId, chunkIdx = 2).shouldBe("ask what you can do for your country.")
                        // Chunk 0 has no previous chunk.
                        resumePrompt(segments, taskId, chunkIdx = 0).shouldBe(null)
                    }
                }
            }
        }
    })
