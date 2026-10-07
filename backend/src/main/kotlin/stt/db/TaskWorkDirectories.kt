package stt.db

import java.nio.file.Path

/**
 * The one place that knows where a task's files live under the data directory (ADR 0024):
 * `$STT_DATA_DIR/work/<task-id>`, holding the original upload at `source` until the task is chunked.
 * Paths only; nothing is created or read here.
 */
class TaskWorkDirectories(
    dataDir: Path,
) {
    private val workRoot = dataDir.resolve("work")

    fun workDir(taskId: Long): Path = workRoot.resolve(taskId.toString())

    fun source(taskId: Long): Path = workDir(taskId).resolve("source")
}
