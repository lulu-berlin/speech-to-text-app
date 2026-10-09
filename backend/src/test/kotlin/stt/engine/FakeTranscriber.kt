package stt.engine

import stt.transcribe.Transcriber
import stt.transcribe.WhisperSegment
import stt.transcribe.WhisperSettings
import java.nio.file.Path

/** A [Transcriber] that returns no segments and never spawns whisper-cli; records every call. */
internal class FakeTranscriber : Transcriber {
    /** The audio file of every call, in call order. */
    val audioFiles = mutableListOf<Path>()

    val probe = ConcurrencyProbe()

    override suspend fun transcribe(
        settings: WhisperSettings,
        audio: Path,
        outputPrefix: Path,
        prompt: String?,
        onProgress: (Double) -> Unit,
    ): List<WhisperSegment> =
        probe.around {
            audioFiles.add(audio)
            emptyList()
        }
}
