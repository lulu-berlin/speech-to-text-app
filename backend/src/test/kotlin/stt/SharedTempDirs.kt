package stt

import java.nio.file.Files
import java.nio.file.Path

/**
 * The temp dirs every process of this user shares by default, as the OS names them: `TMPDIR` (macOS, Unix),
 * `TMP`/`TEMP` (Windows) and `/tmp` on Unix. Found from the environment, never from `java.io.tmpdir`.
 */
internal fun sharedOsTempDirs(): List<Path> =
    (
        listOfNotNull(System.getenv("TMPDIR"), System.getenv("TMP"), System.getenv("TEMP")).map(Path::of) +
            listOfNotNull(Path.of("/tmp").takeIf { !System.getProperty("os.name").startsWith("Windows") })
    ).filter(Files::isDirectory)
        .map { it.toRealPath() }
        .distinct()
