package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Item 57, test-list item "Listing the files under a directory returns the files that remain, without an error, while
 * another writer deletes files and subdirectories in it": the engine's startup clear deletes the scratch tree while
 * the crash-scratch specs poll it (`ProcessingEngineCrashScratchSpec.kt:67` failed a CI run with
 * `NoSuchFileException`), so a directory removed between being listed and being entered must count as gone.
 */
class FilesUnderSpec :
    BehaviorSpec({
        given("a tree of task directories, each holding a file, that another writer deletes") {
            `when`("its files are listed again and again while the deletion runs") {
                then("every listing succeeds with files from the tree only, and the last one is empty") {
                    val root = tempdir("stt-files-under").toPath()
                    val failures = mutableListOf<String>()
                    val strangers = mutableListOf<Path>()
                    val fromTree = { path: Path -> path.startsWith(root) && path.fileName.toString() == CLIP }

                    repeat(ROUNDS) {
                        val taskDirs =
                            (0 until DIRECTORIES).map { i ->
                                root.resolve("task-$i").also { dir ->
                                    Files.createDirectories(dir.resolve("chunking"))
                                    Files.writeString(dir.resolve("chunking").resolve(CLIP), "clip")
                                }
                            }
                        coroutineScope {
                            val deleter =
                                launch(Dispatchers.IO) {
                                    taskDirs.forEach { dir ->
                                        Files.delete(dir.resolve("chunking").resolve(CLIP))
                                        Files.delete(dir.resolve("chunking"))
                                        Files.delete(dir)
                                    }
                                }
                            withContext(Dispatchers.IO) {
                                while (deleter.isActive) {
                                    runCatching { filesUnder(root) }
                                        .onSuccess { listed -> strangers += listed.filterNot(fromTree) }
                                        .onFailure { failures += it.toString() }
                                }
                            }
                        }
                    }

                    failures.shouldBeEmpty()
                    strangers.shouldBeEmpty()
                    filesUnder(root).shouldBeEmpty()
                }
            }
        }
    })

private const val ROUNDS = 20
private const val DIRECTORIES = 300
private const val CLIP = "canonical.flac"
