package stt.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
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
internal class ScratchDir(
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

    /** Removes everything in the scratch directory best-effort, so a crash's leftovers never survive a restart. */
    suspend fun clear() {
        withContext(Dispatchers.IO) {
            if (!Files.isDirectory(dir)) return@withContext
            Files.walk(dir).use { paths ->
                paths
                    .sorted(Comparator.reverseOrder())
                    .filter { it != dir }
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private companion object {
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
