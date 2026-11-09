package stt.db

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import java.util.HexFormat

/**
 * The app's ephemeral scratch directory (item 44): one directory per data directory, under the OS temp dir,
 * `java.io.tmpdir/stt-<fingerprint>/`. Everything a task needs on disk but must not keep lives here — the
 * chunker's scratch (the original written out for decoding, the canonical encode and decoded PCM, the
 * exported chunks), the transient per-chunk whisper inputs and the diarization window decoded from the clip —
 * one directory per task under [taskDir]. Keying it to the data directory keeps one app instance out of another's
 * files, and it is emptied at startup so a crash's leftovers never survive. Every method that touches the disk
 * suspends and runs on [Dispatchers.IO] (AGENTS.md: blocking I/O only there).
 */
internal open class ScratchDir(
    dataDir: Path,
) {
    /** The scratch directory itself; it exists only once a write has run. */
    val dir: Path = Path.of(System.getProperty("java.io.tmpdir")).resolve("stt-${fingerprint(dataDir)}")

    /** The task's scratch directory as a path, whether or not it exists (lookups and cleanup). */
    fun taskDirPath(id: Long): Path = dir.resolve(id.toString())

    /** The directory for task [id]'s scratch files, created when missing. */
    suspend fun taskDir(id: Long): Path = withContext(Dispatchers.IO) { Files.createDirectories(taskDirPath(id)) }

    /** The directory the chunker writes its scratch into; a subdirectory keeps whisper's inputs alone. */
    suspend fun chunkerDir(id: Long): Path = withContext(Dispatchers.IO) { Files.createDirectories(taskDirPath(id).resolve("chunking")) }

    /** The task's original upload, materialized from the database for the chunker (item 44). */
    suspend fun originalFile(id: Long): Path = taskDir(id).resolve("source")

    /** The stored clip's rows one diarization window needs, materialized as the FLAC the decoder reads (item 43). */
    suspend fun diarizeWindow(id: Long): Path = taskDir(id).resolve("diarize-window.flac")

    /** The PCM [diarizeWindow] decodes to. */
    suspend fun diarizePcm(id: Long): Path = taskDir(id).resolve("diarize-window.f32")

    /**
     * Deletes [path] if it exists. Every cleanup deletes through here, so a spec can make one fail (item 95).
     */
    open suspend fun delete(path: Path) {
        withContext(Dispatchers.IO) { Files.deleteIfExists(path) }
    }

    /**
     * Deletes [path] best effort (item 95): an [IOException] is logged with the path and swallowed, so a cleanup
     * never replaces the error in flight; cancellation is not absorbed.
     */
    suspend fun deleteBestEffort(path: Path) {
        try {
            delete(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            logger.warn("Could not delete {}; leaving it for the next startup", path, e)
        }
    }

    /** Removes [dir] and everything in it, best effort, once its run has ended (item 95). */
    suspend fun deleteTreeBestEffort(dir: Path) {
        deleteTree(dir, keepRoot = false)
    }

    /** Removes everything in the scratch directory best-effort, so a crash's leftovers never survive a restart. */
    open suspend fun clear() {
        deleteTree(dir, keepRoot = true)
    }

    /**
     * Removes [root] and everything in it, best effort (item 95): [keepRoot] leaves [root] itself. A failed delete
     * is logged with its path by [deleteBestEffort], and a tree that cannot be listed at all is logged here and
     * left for the next startup. Cancellation is not absorbed.
     */
    private suspend fun deleteTree(
        root: Path,
        keepRoot: Boolean,
    ) {
        try {
            withContext(Dispatchers.IO) {
                if (!Files.isDirectory(root)) return@withContext
                Files.walk(root).use { paths ->
                    for (path in paths.sorted(Comparator.reverseOrder()).toList()) {
                        if (keepRoot && path == root) continue
                        deleteBestEffort(path)
                    }
                }
            }
        } catch (e: UncheckedIOException) {
            // Files.walk reports a directory it cannot list as unchecked, not as an IOException.
            logger.warn("Could not list {} for removal; leaving it for the next startup", root, e.cause ?: e)
        } catch (e: IOException) {
            logger.warn("Could not list {} for removal; leaving it for the next startup", root, e)
        }
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(ScratchDir::class.java)

        /** A stable, short fingerprint of [dataDir]'s absolute path, so the scratch directory is per data directory. */
        fun fingerprint(dataDir: Path): String {
            val digest =
                MessageDigest.getInstance("SHA-256").digest(
                    dataDir
                        .toAbsolutePath()
                        .normalize()
                        .toString()
                        .toByteArray(),
                )
            return HexFormat.of().formatHex(digest).take(16)
        }
    }
}
