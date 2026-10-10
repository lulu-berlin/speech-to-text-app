package stt.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import stt.db.ChunkRepository
import stt.db.TaskWorkDirectories
import stt.process.ProcessRunner
import java.nio.file.Files
import java.nio.file.Path

/**
 * The real [AudioJoiner]: materializes each stored chunk FLAC into the task's work directory and joins them
 * with ffmpeg's concat demuxer (`-c copy`, no re-encode; ADR 0024) into [target]. The double-quoted input
 * names keep the concat list safe regardless of the chunk names (`-safe 0` is for absolute paths; these are
 * relative to the list file, in the same work directory).
 */
class FfmpegAudioJoiner(
    private val runner: ProcessRunner,
    private val chunks: ChunkRepository,
    private val workDirectories: TaskWorkDirectories,
) : AudioJoiner {
    override suspend fun join(
        taskId: Long,
        target: Path,
    ) {
        val workDir = workDirectories.workDir(taskId)
        val inputs =
            chunks.findByTask(taskId).map { chunk ->
                val file = workDir.resolve("join-${chunk.idx}.flac")
                withContext(Dispatchers.IO) {
                    Files.newOutputStream(file).use { chunks.copyFlacTo(taskId, chunk.idx, it) }
                }
                file
            }
        val listFile = workDir.resolve("join-list.txt")
        Files.write(listFile, inputs.map { "file '${it.fileName}'" })
        try {
            val result =
                runner.run(
                    FFMPEG +
                        listOf(
                            "-y",
                            "-loglevel",
                            "error",
                            "-f",
                            "concat",
                            "-safe",
                            "0",
                            "-i",
                            listFile.toString(),
                            "-c",
                            "copy",
                            target.toString(),
                        ),
                )
            result.expectSuccess("join the chunks of task $taskId")
        } finally {
            // Best effort: the materialized copies are the joiner's own; the startup sweep removes leftovers.
            inputs.forEach { file ->
                runCatching { Files.deleteIfExists(file) }
            }
            runCatching { Files.deleteIfExists(listFile) }
        }
    }
}
