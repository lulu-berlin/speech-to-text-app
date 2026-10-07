package stt.diarization

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** The CAM++ speaker-embedding model (ADR 0003): one ONNX Runtime session on the CPU and the model's [metadata]. */
internal class SpeakerModel private constructor(
    internal val session: OrtSession,
    val metadata: SpeakerModelMetadata,
) : AutoCloseable {
    override fun close() = session.close()

    companion object {
        /**
         * Session options: [intraOpThreads] when set, otherwise ONNX Runtime's default. The count has no read-back
         * in onnxruntime 1.30.0's Java API, so end-to-end acceptance is covered by the Integration tests.
         */
        internal fun sessionOptions(intraOpThreads: Int? = null): OrtSession.SessionOptions =
            OrtSession.SessionOptions().apply {
                if (intraOpThreads != null) setIntraOpNumThreads(intraOpThreads)
            }

        /** Creates the session from the model file at [path] (blocking file I/O, so on [Dispatchers.IO]) and reads its metadata. */
        suspend fun load(
            path: Path,
            intraOpThreads: Int? = null,
        ): SpeakerModel =
            withContext(Dispatchers.IO) {
                val session =
                    sessionOptions(intraOpThreads).use { options ->
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
