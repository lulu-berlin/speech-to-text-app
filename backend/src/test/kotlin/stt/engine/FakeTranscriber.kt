package stt.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import stt.transcribe.Transcriber
import stt.transcribe.WhisperSegment
import stt.transcribe.WhisperSettings
import java.nio.file.Path

/** A [Transcriber] that returns no segments and never spawns whisper-cli; records every call. */
internal class FakeTranscriber : Transcriber {
    /** The audio file of every call, in call order. */
    val audioFiles = mutableListOf<Path>()

    /** The audio file of every call, as the call starts, so a test can wait for the worker to be inside one. */
    val entered = Channel<Path>(Channel.UNLIMITED)

    /** When set, every call waits for it before returning; a test clears it to let later calls run through. */
    var hold: CompletableDeferred<Unit>? = null

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
            entered.send(audio)
            hold?.await()
            emptyList()
        }
}
