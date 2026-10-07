package stt.diarization

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** The CAM++ speaker-embedding model (ADR 0003): one ONNX Runtime session on the CPU and the model's [metadata]. */
internal class SpeakerModel private constructor(
    private val session: OrtSession,
    val metadata: SpeakerModelMetadata,
) : AutoCloseable {
    override fun close() = session.close()

    companion object {
        /** Creates the session from the model file at [path] (blocking file I/O, so on [Dispatchers.IO]) and reads its metadata. */
        suspend fun load(path: Path): SpeakerModel =
            withContext(Dispatchers.IO) {
                val session =
                    OrtSession.SessionOptions().use { options ->
                        OrtEnvironment.getEnvironment().createSession(path.toString(), options)
                    }
                try {
                    SpeakerModel(session, SpeakerModelMetadata.parse(session.metadata.customMetadata))
                } catch (e: Exception) {
                    session.close()
                    throw e
                }
            }
    }
}
