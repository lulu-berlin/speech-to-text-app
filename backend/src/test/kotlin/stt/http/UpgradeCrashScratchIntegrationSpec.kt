package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import stt.Integration
import stt.audio.sineFlac
import stt.awaitServing
import stt.engine.filesUnder
import stt.freePort
import stt.startBackend
import java.util.concurrent.TimeUnit

/**
 * Item 44, test-list item "(discovered) A crash in the middle of the upgrade's backfill leaves no temporary files
 * behind after the next start". The app runs as a real process with its own temp directory: it is killed while the
 * upgrade rebuilds a legacy task's clip (V10), then started again on the same data directory, which completes the
 * upgrade. Whatever the killed run left must be gone once the restarted app is up.
 */
class UpgradeCrashScratchIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

        given("a database at V7 with a finished task whose only audio is a 10 min chunk FLAC") {
            `when`("the app is killed while the upgrade rebuilds that clip, then started again") {
                then("the restarted app leaves no temporary file, though the killed one left some") {
                    val dataDir = tempdir("stt-upgrade-crash").toPath()
                    val fixtures = tempdir("stt-upgrade-fixtures").toPath()
                    // The app's own temp directory, which only these runs use; native libraries unpack elsewhere.
                    val appTemp = tempdir("stt-upgrade-tmp").toPath()
                    val nativeTemp = tempdir("stt-upgrade-native").toPath()
                    legacyDatabase(
                        dataDir,
                        statements =
                            listOf(
                                "INSERT INTO tasks " +
                                    "(id, original_filename, content_type, duration_ms, status, speakers_mode, created_at, finished_at) " +
                                    "VALUES (1, 'long.wav', 'audio/wav', 600000, 'COMPLETED', 'off', 1, 1000)",
                                "INSERT INTO audio (task_id, size_bytes, sha256) VALUES (1, 1, 'long-sha')",
                                "INSERT INTO chunks (task_id, idx, start_ms, end_ms, status) VALUES (1, 0, 0, 600000, 'DONE')",
                            ),
                        chunkFlacs = mapOf(1L to listOf(sineFlac(fixtures, "chunk-0", "440", "600"))),
                    )

                    // The first run: killed as soon as the backfill has written its first temporary file.
                    val killed =
                        startBackend(dataDir, freePort(), fixtures.resolve("killed.log"), appTemp, nativeTemp)
                    try {
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
                        while (filesUnder(appTemp).isEmpty() && killed.isAlive && System.nanoTime() < deadline) delay(5)
                    } finally {
                        killed.destroyForcibly().waitFor()
                    }
                    // The crash state is real: the killed run left temporary files behind.
                    filesUnder(appTemp).shouldNotBeEmpty()

                    // The next start finishes the upgrade and serves.
                    val port = freePort()
                    val restarted =
                        startBackend(dataDir, port, fixtures.resolve("restarted.log"), appTemp, nativeTemp)
                    try {
                        awaitServing(port) shouldBe true
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                        while (filesUnder(appTemp).isNotEmpty() && System.nanoTime() < deadline) delay(50)
                        filesUnder(appTemp).shouldBeEmpty()
                    } finally {
                        restarted.destroy()
                        restarted.waitFor(30, TimeUnit.SECONDS)
                        restarted.destroyForcibly()
                    }
                }
            }
        }
    })
