package stt.db.migration

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/**
 * Removes [dir] and everything in it, [dir] included; a directory that is not there is left alone. Blocking, like
 * the class migrations that use it (Flyway runs them on `Dispatchers.IO` from `Database.open`).
 */
internal fun deleteTree(dir: Path) {
    if (!Files.isDirectory(dir)) return
    Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
}
