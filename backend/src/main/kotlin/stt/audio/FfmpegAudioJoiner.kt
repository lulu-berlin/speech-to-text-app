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
 * with ffmpeg's concat demuxer into [target], re-encoding to FLAC (ADR 0045, amending ADR 0024's `-c copy`).
 * The re-encode is lossless, and it is what makes the file seekable: a copied concat keeps the first chunk's
 * length in its header and restarts the frames' positions at every chunk. The double-quoted input names keep
 * the concat list safe regardless of the chunk names (`-safe 0` is for absolute paths; these are relative to
 * the list file, in the same work directory).
 *
 * The work directory is created when missing (item 29): a COMPLETED task's was removed by the engine, but
 * its chunks are still in the database and its audio must still join. Only the joiner's own scratch is
 * removed afterwards, never the directory — it may be an active task's; the startup sweep removes an empty
 * one left behind for a finished task.
 *
 * The join runs its own fresh [ProcessRunner], one per call (item 37): the joiner serves an HTTP request,
 * not an engine task, so it must not claim the engine's single-flight runner (ADR 0020) — a join would
 * otherwise answer 500 "A run is already in flight" whenever the engine held the runner, and two concurrent
 * joins would collide with each other. A per-join runner keeps every join independent, with the same
 * cancellation cleanup (the tree is killed when the request is cancelled).
 */
class FfmpegAudioJoiner(
    private val chunks: ChunkRepository,
    private val workDirectories: TaskWorkDirectories,
) : AudioJoiner {
    override suspend fun join(
        taskId: Long,
        target: Path,
    ) {
        val workDir = workDirectories.workDir(taskId)
        withContext(Dispatchers.IO) { Files.createDirectories(workDir) }
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
                ProcessRunner().run(
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
                            "-c:a",
                            "flac",
                            // The output format is named, not inferred: the cache hands over a temporary
                            // `….flac.part` name, from which ffmpeg cannot pick a muxer (item 29).
                            "-f",
                            "flac",
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
