package stt.diarization

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.Integration
import stt.config.Config
import stt.config.TEST_DATA_DIR
import stt.db.Segment

class EmbeddingsSpec :
    BehaviorSpec({
        tags(Integration)

        given("a segment list and an empty PCM") {
            `when`("embedding") {
                then("every segment gets an all-zero embedding of the model's output dim, in segment order") {
                    // Oracle embed_windows: total <= 0 -> np.zeros((len(segments), 192)).
                    val path = Config.fromEnv(System.getenv() + ("STT_DATA_DIR" to TEST_DATA_DIR)).speakerModel
                    SpeakerModel.load(path).use { model ->
                        val segments = listOf(segment(0, 100), segment(4000, 4500), segment(9000, 10_000))

                        val embeddings = Embeddings(model).embed(FloatArray(0), segments)

                        embeddings.size.shouldBe(3)
                        embeddings.forEach { embedding ->
                            embedding.size.shouldBe(512)
                            embedding.forEach { sample -> sample.shouldBe(0f) }
                        }
                    }
                }
            }
        }
    })

private fun segment(
    startMs: Long,
    endMs: Long,
): Segment = Segment(chunkIdx = 0, seq = 0, startMs = startMs, endMs = endMs, text = "", speaker = null)
