package stt.diarization

import kotlinx.coroutines.withTimeout
import stt.audio.AudioDecoder
import stt.process.ProcessRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** Decodes [source] with the app's decoder (mono 16 kHz float32, as item 11 feeds the embedder) into [pcm] and reads it. */
internal suspend fun decodePcm(
    source: Path,
    pcm: Path,
): FloatArray {
    withTimeout(30.seconds) { AudioDecoder(ProcessRunner()).decode(source, pcm) }
    val floats = ByteBuffer.wrap(Files.readAllBytes(pcm)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    return FloatArray(floats.remaining()) { floats.get(it) }
}

/** The committed LibriSpeech clips (`src/test/resources/librispeech`, ADR 0026, docs/attributions.md), from the test classpath. */
internal fun librispeechClips(): Path =
    Path.of(checkNotNull(Embeddings::class.java.getResource("/librispeech")) { "librispeech/ missing from the test classpath" }.toURI())

/** The committed LibriSpeech clips [ids], each decoded with the app's decoder into [workDir], by id in [ids] order. */
internal suspend fun decodeLibrispeech(
    workDir: Path,
    ids: List<String>,
): Map<String, FloatArray> {
    val clips = librispeechClips()
    return ids.associateWith { id -> decodePcm(clips.resolve("$id.flac"), workDir.resolve("$id.f32")) }
}
