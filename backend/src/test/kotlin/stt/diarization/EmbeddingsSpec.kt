package stt.diarization

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import stt.Integration
import stt.audio.AudioDecoder
import stt.config.realSpeakerModelPath
import stt.db.Segment
import stt.process.ProcessRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class EmbeddingsSpec :
    BehaviorSpec({
        tags(Integration)

        given("a segment list and an empty PCM") {
            `when`("embedding") {
                then("every segment gets an all-zero embedding of the model's output dim, in segment order") {
                    // Oracle embed_windows: total <= 0 -> np.zeros((len(segments), 192)).
                    SpeakerModel.load(realSpeakerModelPath()).use { model ->
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
                    val pcm = FloatArray(192_000) // 12 s
                    val tone = { frequency: Double, fromMs: Long, toMs: Long ->
                        for (i in (fromMs * 16) until (toMs * 16)) pcm[i.toInt()] = (0.05 * sin(2 * PI * frequency * i / 16_000)).toFloat()
                    }
                    tone(440.0, 0, 1200)
                    tone(3000.0, 3000, 4800)
                    tone(440.0, 10_000, 11_200)

                    SpeakerModel.load(realSpeakerModelPath()).use { model ->
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
                        dot(embeddings[0], embeddings[1]).shouldBeLessThan(0.9999f)
                    }
                }
            }
        }
        given("the real model, two segments, and a pause gate") {
            `when`("pausing after the first window") {
                then("embedding suspends between windows and resumes with the next window") {
                    // ADR 0004: in-JVM phases pause cooperatively through a pause gate checked between windows.
                    val pcm = FloatArray(64_000) { i -> (0.05 * sin(2 * PI * 440 * i / 16_000)).toFloat() }
                    SpeakerModel.load(realSpeakerModelPath()).use { model ->
                        val embedder = Embeddings(model)
                        val gate = PauseGate()
                        val job =
                            async {
                                embedder.embed(pcm, listOf(segment(0, 1200), segment(30_000, 31_200)), gate) { done, _ ->
                                    if (done == 1) gate.pause()
                                }
                            }

                        val finishedWhilePaused = withTimeoutOrNull(500.milliseconds) { job.await() }
                        finishedWhilePaused.shouldBe(null) // still suspended at the checkpoint between the windows

                        gate.resume()

                        job.await().size.shouldBe(2)
                    }
                }
            }
        }
        given("three LibriSpeech test-clean clips: two of speaker 1089 from different chapters, one of speaker 1188") {
            `when`("each whole clip is embedded") {
                then("the two clips of speaker 1089 are more similar to each other than either is to the clip of speaker 1188") {
                    // Spec (item 08 acceptance): same-speaker cosine > different-speaker cosine. Embeddings are
                    // L2-normalized, so the cosine is the dot product. Clips: docs/attributions.md (CC BY 4.0, ADR 0026).
                    val pcms = decodeLibrispeech(tempdir("librispeech").toPath())
                    SpeakerModel.load(realSpeakerModelPath()).use { model ->
                        val embedder = Embeddings(model)
                        val (speakerA1, speakerA2, speakerB) = pcms.values.map { pcm -> embedder.embed(pcm, listOf(wholeClip(pcm)))[0] }

                        val same = dot(speakerA1, speakerA2)
                        val different = listOf(dot(speakerA1, speakerB), dot(speakerA2, speakerB))

                        withClue("same-speaker cosine $same, different-speaker cosines $different") {
                            different.forEach { same.shouldBeGreaterThan(it) }
                        }
                    }
                }
            }
        }
        given("a 600 ms excerpt starting 2 s into each LibriSpeech clip, zero-padded to the 2 s window, and the three whole clips") {
            `when`("the excerpts and the whole clips are embedded") {
                then("each excerpt is more similar to every whole clip of its own speaker than to the whole clip of the other speaker") {
                    // Review round 1, finding 1: CAM++ (normalize_samples = 1) takes samples in [-1, 1] unscaled, as
                    // sherpa-onnx does. A wrong scale shifts every log-mel value of frames with sound but leaves the
                    // zero padding at the log floor, so global-mean normalization hides it only for unpadded windows.
                    val pcms = decodeLibrispeech(tempdir("librispeech").toPath())
                    SpeakerModel.load(realSpeakerModelPath()).use { model ->
                        val embedder = Embeddings(model)
                        val whole = pcms.mapValues { (_, pcm) -> embedder.embed(pcm, listOf(wholeClip(pcm)))[0] }
                        val excerpts = pcms.mapValues { (_, pcm) -> embedder.embed(pcm, listOf(segment(2000, 2600)))[0] }

                        val speaker = { id: String -> id.substringBefore('-') }
                        for ((id, excerpt) in excerpts) {
                            val own = whole.filterKeys { speaker(it) == speaker(id) }.values.map { dot(excerpt, it) }
                            val other = whole.filterKeys { speaker(it) != speaker(id) }.values.map { dot(excerpt, it) }
                            withClue("excerpt of $id: own-speaker cosines $own, other-speaker cosines $other") {
                                own.min().shouldBeGreaterThan(other.max())
                            }
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

/** The segment covering all of [pcm], in whole milliseconds. */
private fun wholeClip(pcm: FloatArray): Segment = segment(0, pcm.size / 16L)

private fun dot(
    a: FloatArray,
    b: FloatArray,
): Float = a.indices.map { a[it] * b[it] }.sum()

/** Decodes [source] with the app's decoder (mono 16 kHz float32, as item 11 feeds the embedder) into [pcm] and reads it. */
private suspend fun decodePcm(
    source: Path,
    pcm: Path,
): FloatArray {
    withTimeout(30.seconds) { AudioDecoder(ProcessRunner()).decode(source, pcm) }
    val floats = ByteBuffer.wrap(Files.readAllBytes(pcm)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    return FloatArray(floats.remaining()) { floats.get(it) }
}

/** The committed LibriSpeech utterances (docs/attributions.md): two of speaker 1089 from different chapters, one of speaker 1188. */
private val LIBRISPEECH_IDS = listOf("1089-134686-0002", "1089-134691-0023", "1188-133604-0025")

/**
 * The committed LibriSpeech clips (`src/test/resources/librispeech`, ADR 0026, read from the test classpath), each decoded
 * with the app's decoder into [workDir], by utterance id in [LIBRISPEECH_IDS] order.
 */
private suspend fun decodeLibrispeech(workDir: Path): Map<String, FloatArray> {
    val resource = checkNotNull(Embeddings::class.java.getResource("/librispeech")) { "librispeech/ missing from the test classpath" }
    val clips = Path.of(resource.toURI())
    return LIBRISPEECH_IDS.associateWith { id -> decodePcm(clips.resolve("$id.flac"), workDir.resolve("$id.f32")) }
}
