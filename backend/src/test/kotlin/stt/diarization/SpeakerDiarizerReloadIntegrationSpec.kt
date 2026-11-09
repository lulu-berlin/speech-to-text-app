package stt.diarization

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.Integration
import stt.config.realSpeakerModelPath
import stt.db.Segment
import stt.db.SegmentRepository
import stt.db.withDatabase
import java.nio.file.Files

/**
 * Item 95: a speaker-model load that fails after a thread-count change must leave the working session in place —
 * loading before closing the old model — so the next task at the old thread count still diarizes instead of using a
 * closed session.
 */
class SpeakerDiarizerReloadIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

        given("a diarizer that loaded its model at 4 threads, whose model file then becomes unreadable") {
            `when`("a task asks for 8 threads and the load fails") {
                then("the next call at 4 threads still embeds") {
                    withDatabase { db ->
                        val modelBytes = Files.readAllBytes(realSpeakerModelPath())
                        val modelFile = Files.createTempDirectory("stt-model").resolve("model.onnx")
                        // Written, not copied: Files.copy keeps the read-only mode of the Nix store's file.
                        Files.write(modelFile, modelBytes)
                        SpeakerDiarizer(modelFile, SegmentRepository(db)).use { diarizer ->
                            // The model loads at 4 threads and is cached.
                            diarizer.embeddings(4)

                            // The file becomes unreadable: the load for another thread count fails.
                            Files.writeString(modelFile, "not a model")
                            shouldThrow<Exception> { diarizer.embeddings(8) }

                            // Readable again: the old thread count must still embed, not use a closed session.
                            Files.write(modelFile, modelBytes)
                            val vector =
                                diarizer.embeddings(4).embed(
                                    SilentPcm(totalSamples = 32_000),
                                    listOf(Segment(chunkIdx = 0, seq = 0, startMs = 0, endMs = 1_000, text = " x ", speakerId = null)),
                                )

                            vector.size shouldBe 1
                            // The CAM++ metadata's output_dim (ADR 0003).
                            vector.single().size shouldBe 512
                        }
                    }
                }
            }
        }
    })

/** 2 s of silence at 16 kHz: enough for one segment's window, and no audio to read. */
private class SilentPcm(
    override val totalSamples: Int,
) : PcmSource {
    override suspend fun samples(
        fromSample: Int,
        toSample: Int,
    ): FloatArray = FloatArray(toSample - fromSample)
}
