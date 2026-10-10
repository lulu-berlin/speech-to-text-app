package stt

import java.nio.file.Files
import java.nio.file.Path

/** A file of the API contract in `docs/api/` (item 12), wherever the test JVM's working directory sits in the repository. */
internal fun docsApiFile(fileName: String): Path {
    val candidates = listOf(Path.of("../docs/api", fileName), Path.of("docs/api", fileName))
    return candidates.firstOrNull(Files::exists)
        ?: error("$fileName not found; working directory is ${Path.of("").toAbsolutePath()}")
}
