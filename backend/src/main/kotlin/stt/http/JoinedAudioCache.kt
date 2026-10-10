package stt.http

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import stt.db.TaskWorkDirectories
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * The `$STT_DATA_DIR/tmp/<task-id>.flac` cache of item 12's `/audio` route: built once per task (under a
 * temporary name, then renamed atomically), one build per task at a time, and removed on task deletion or at
 * the next startup (the database open clears `tmp/`).
 */
class JoinedAudioCache(
    private val workDirectories: TaskWorkDirectories,
) {
    private val building = ConcurrentHashMap<Long, Mutex>()

    /**
     * The task's joined FLAC, building it when missing: [build] writes the file to a temporary name next to
     * the cache, and the cache is renamed into place. It is served while a concurrent first request builds it
     * on this task's own lock, so the join runs once.
     */
    suspend fun getOrBuild(
        taskId: Long,
        build: suspend (target: Path) -> Unit,
    ): Path {
        val cached = workDirectories.cachedAudio(taskId)
        if (Files.exists(cached)) return cached
        val lock = building.computeIfAbsent(taskId) { Mutex() }
        return lock.withLock {
            if (Files.exists(cached)) {
                cached
            } else {
                val temp = Files.createTempFile(cached.parent, "task-$taskId-", ".flac.part")
                build(temp)
                Files.move(temp, cached, StandardCopyOption.ATOMIC_MOVE)
                cached
            }
        }
    }
}
