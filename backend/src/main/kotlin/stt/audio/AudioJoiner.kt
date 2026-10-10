package stt.audio

import java.nio.file.Path

/**
 * Joins a task's stored chunk FLACs (ADR 0024) into one FLAC written to [target]. Item 12's `/audio` route
 * builds its `tmp/<task-id>.flac` cache through this seam; the production implementation is
 * [FfmpegAudioJoiner], route tests provide a recording fake so no real ffmpeg runs in unit tests.
 */
fun interface AudioJoiner {
    suspend fun join(
        taskId: Long,
        target: Path,
    )
}
