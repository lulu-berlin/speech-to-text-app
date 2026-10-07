package stt.diarization

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import stt.Integration
import stt.config.Config
import stt.config.TEST_DATA_DIR
import stt.db.Segment
import kotlin.math.PI
import kotlin.math.sin

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
        given("a PCM and segments at different positions") {
            `when`("embedding them all at once and each one alone") {
                then("each position in the result holds the embedding of the matching segment, in order") {
                    // Ported windows stay in segment order: a length-sorting batching (as in oracle embed_windows)
                    // would reorder these three segments, whose window lengths differ (1200 ms, 1800 ms, 1200 ms).
                    val path = Config.fromEnv(System.getenv() + ("STT_DATA_DIR" to TEST_DATA_DIR)).speakerModel
                    val pcm = FloatArray(192_000) // 12 s
                    val tone = { frequency: Double, fromMs: Long, toMs: Long ->
                        for (i in (fromMs * 16) until (toMs * 16)) pcm[i.toInt()] = (0.05 * sin(2 * PI * frequency * i / 16_000)).toFloat()
                    }
                    tone(440.0, 0, 1200)
                    tone(3000.0, 3000, 4800)
                    tone(440.0, 10_000, 11_200)

                    SpeakerModel.load(path).use { model ->
                        val embedder = Embeddings(model)
                        val segments = listOf(segment(0, 1200), segment(3000, 4800), segment(10_000, 11_200))

                        val embeddings = embedder.embed(pcm, segments)

                        embeddings.size.shouldBe(3)
                        embeddings.indices.forEach { index ->
                            val alone = embedder.embed(pcm, listOf(segments[index]))[0]
                            embeddings[index].indices.forEach { component ->
                                embeddings[index][component].shouldBe(alone[component].plusOrMinus(1e-4f))
                            }
                        }
                        // Sanity: the 3 kHz middle segment is not the same vector as the 440 Hz first one.
                        embeddings[0]
                            .indices
                            .map { embeddings[0][it] * embeddings[1][it] }
                            .sum()
                            .shouldBeLessThan(0.9999f)
                    }
                }
            }
        }
    })

private fun segment(
    startMs: Long,
    endMs: Long,
): Segment = Segment(chunkIdx = 0, seq = 0, startMs = startMs, endMs = endMs, text = "", speaker = null)
