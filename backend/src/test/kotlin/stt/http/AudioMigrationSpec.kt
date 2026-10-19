package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.flywaydb.core.Flyway
import stt.Integration
import stt.audio.sineFlac
import stt.config.Config
import stt.config.realSpeakerModelPath
import stt.db.ChunkRepository
import stt.db.ChunkStatus
import stt.db.Database
import stt.db.Segment
import stt.db.SegmentRepository
import stt.db.TaskRepository
import stt.db.singleValue
import stt.engine.awaitUntil
import stt.queue.TaskStatus
import stt.transcribe.transcriptFixtures
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Item 44, test-list item "Migration: an existing database upgrades and its finished tasks still play and
 * transcribe correctly": an existing installation's database (schema V7, ADR 0024 — a finished task whose
 * audio is only its stored chunk FLACs, with no canonical clip) upgrades and the task still plays: `/audio`
 * serves audio, not `404 no-audio`.
 */
class AudioMigrationSpec :
    BehaviorSpec({
        tags(Integration)

        given("a database at V7 whose finished task has only stored chunk FLACs (no canonical clip)") {
            `when`("the app opens it and the task's audio is requested") {
                then("the task still plays: /audio serves its audio as audio/flac") {
                    val dataDir = tempdir("stt-audio-migration").toPath()
                    val fixtures = tempdir("stt-migration-fixtures").toPath()
                    // The legacy shape: two chunk FLACs (0.3 s at 440 Hz, then 0.5 s at 880 Hz; 800 ms
                    // together) and nothing else — the audio an existing task really has.
                    val chunk0 = sineFlac(fixtures, "chunk-0", "440", "0.3")
                    val chunk1 = sineFlac(fixtures, "chunk-1", "880", "0.5")
                    seedLegacyTask(dataDir, chunk0, chunk1, durationMs = 800)

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        startApplication()

                        // Whatever the migration rebuilt must be the task's audio: 800 ms × 16 000 samples
                        // per second = 12 800 samples, by hand.
                        servedSamples(taskId = 1, fixtures) shouldBe 12_800L
                    }
                }
            }
        }

        given("a database at V7 whose finished task's only chunk FLAC is 0.1 s: 1 600 samples, less than one 250 ms frame") {
            `when`("the app opens it and the task's audio is requested") {
                then("the upgrade backfills that task too, and it plays its 1 600 samples") {
                    val dataDir = tempdir("stt-audio-migration").toPath()
                    val fixtures = tempdir("stt-migration-fixtures").toPath()
                    legacyDatabase(
                        dataDir,
                        statements =
                            listOf(
                                "INSERT INTO tasks " +
                                    "(id, original_filename, content_type, duration_ms, status, speakers_mode, created_at, finished_at) " +
                                    "VALUES (1, 'short.wav', 'audio/wav', 100, 'COMPLETED', 'off', 1, 1000)",
                                "INSERT INTO audio (task_id, size_bytes, sha256) VALUES (1, 1, 'short-sha')",
                                "INSERT INTO chunks (task_id, idx, start_ms, end_ms, status) VALUES (1, 0, 0, 100, 'DONE')",
                            ),
                        chunkFlacs = mapOf(1L to listOf(sineFlac(fixtures, "chunk-0", "440", "0.1"))),
                    )

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        startApplication()

                        // 0.1 s × 16 000 samples per second = 1 600 samples, by hand.
                        servedSamples(taskId = 1, fixtures) shouldBe 1_600L
                    }
                }
            }
        }

        given("a database at V7 with a finished task and an unfinished one (chunk 0 DONE, chunk 1 PENDING), both only chunk FLACs") {
            `when`("the app opens it with the real ffmpeg and whisper-cli") {
                then("the per-chunk audio is gone, the finished task plays, and the unfinished one completes from its clip") {
                    val (whisperModel, _) = transcriptFixtures()
                    val dataDir = tempdir("stt-audio-migration").toPath()
                    val fixtures = tempdir("stt-migration-fixtures").toPath()
                    val finished = listOf(sineFlac(fixtures, "done-0", "440", "0.3"), sineFlac(fixtures, "done-1", "880", "0.5"))
                    val unfinished = listOf(sineFlac(fixtures, "open-0", "440", "1.5"), sineFlac(fixtures, "open-1", "660", "2"))
                    seedLegacyQueue(dataDir, finished, unfinished)

                    testApplication {
                        application { sttApp(Config(dataDir, realSpeakerModelPath(), whisperModel)) }
                        startApplication()
                        val db = application.dependencies.resolve<Database>()
                        val tasks = TaskRepository(db)

                        // The upgrade kept no per-chunk audio: chunks are windows into each task's stored clip.
                        singleValue(db, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'chunk_audio'") shouldBe "0"

                        // 0.3 s + 0.5 s at 16 000 samples per second = 12 800 samples, by hand.
                        servedSamples(taskId = 1, fixtures) shouldBe 12_800L

                        awaitUntil { tasks.find(2)?.task?.status in setOf(TaskStatus.COMPLETED, TaskStatus.FAILED) }
                        val task = tasks.find(2)?.task
                        (task?.status to task?.error) shouldBe (TaskStatus.COMPLETED to null)
                        ChunkRepository(db).findByTask(2).map { it.status } shouldBe listOf(ChunkStatus.DONE, ChunkStatus.DONE)
                        // Chunk 0's transcript from before the upgrade is kept; only chunk 1 was transcribed now.
                        SegmentRepository(db).findByTask(2).first() shouldBe
                            Segment(chunkIdx = 0, seq = 0, startMs = 0, endMs = 1_000, text = " hello ", speakerId = null)
                    }
                }
            }
        }

        given("a V7-era data directory: a chunked COMPLETED task, and a QUEUED and a CANCELED task never chunked, originals in work/") {
            `when`("the app opens it")
            {
                then("the never-chunked tasks end FAILED as not migrated, the chunked one still plays, and work/ and tmp/ are gone") {
                    val dataDir = tempdir("stt-audio-migration").toPath()
                    val fixtures = tempdir("stt-migration-fixtures").toPath()
                    legacyDatabase(
                        dataDir,
                        statements =
                            listOf(
                                // Paused, so no run of today's engine touches the queued task before it is looked at.
                                "INSERT OR REPLACE INTO queue_state (id, paused) VALUES (1, 1)",
                                "INSERT INTO tasks " +
                                    "(id, original_filename, content_type, duration_ms, status, speakers_mode, created_at, finished_at) " +
                                    "VALUES (1, 'done.wav', 'audio/wav', 300, 'COMPLETED', 'off', 1, 1000)",
                                "INSERT INTO tasks " +
                                    "(id, original_filename, content_type, status, speakers_mode, created_at, queue_position) " +
                                    "VALUES (2, 'waiting.wav', 'audio/wav', 'QUEUED', 'off', 2, 0)",
                                "INSERT INTO tasks (id, original_filename, content_type, status, speakers_mode, created_at, finished_at) " +
                                    "VALUES (3, 'canceled.wav', 'audio/wav', 'CANCELED', 'off', 3, 500)",
                                "INSERT INTO audio (task_id, size_bytes, sha256) VALUES (1, 1, 'done-sha')",
                                "INSERT INTO audio (task_id, size_bytes, sha256) VALUES (2, 1, 'waiting-sha')",
                                "INSERT INTO audio (task_id, size_bytes, sha256) VALUES (3, 1, 'canceled-sha')",
                                "INSERT INTO chunks (task_id, idx, start_ms, end_ms, status) VALUES (1, 0, 0, 300, 'DONE')",
                            ),
                        chunkFlacs = mapOf(1L to listOf(sineFlac(fixtures, "chunk-0", "440", "0.3"))),
                    )
                    // Before item 44 an original waited in work/<id>/source until chunking; tmp/ held upload spools.
                    for (id in listOf(2, 3)) {
                        Files.createDirectories(dataDir.resolve("work/$id"))
                        Files.writeString(dataDir.resolve("work/$id/source"), "original of task $id")
                    }
                    Files.createDirectories(dataDir.resolve("tmp"))
                    Files.writeString(dataDir.resolve("tmp/upload.spool"), "a spool")

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        startApplication()
                        val tasks = TaskRepository(application.dependencies.resolve<Database>())

                        val notMigrated =
                            "This task's audio from before the upgrade was not migrated. Delete the task and upload the file again."
                        val waiting = tasks.find(2)?.task
                        (waiting?.status to waiting?.error) shouldBe (TaskStatus.FAILED to notMigrated)
                        (waiting?.finishedAt != null) shouldBe true
                        val canceled = tasks.find(3)?.task
                        Triple(canceled?.status, canceled?.error, canceled?.finishedAt) shouldBe
                            Triple(TaskStatus.FAILED, notMigrated, 500L)

                        // 0.3 s × 16 000 samples per second = 4 800 samples, by hand.
                        servedSamples(taskId = 1, fixtures) shouldBe 4_800L
                        Files.exists(dataDir.resolve("work")) shouldBe false
                        Files.exists(dataDir.resolve("tmp")) shouldBe false
                    }
                }
            }
        }
    })

/**
 * Requests task [taskId]'s audio, checks it is served as `audio/flac`, and returns its sample count from
 * the served FLAC's STREAMINFO (written to a file under [fixtures]).
 */
private suspend fun ApplicationTestBuilder.servedSamples(
    taskId: Long,
    fixtures: Path,
): Long {
    val response = client.get("/api/tasks/$taskId/audio")
    response.status shouldBe HttpStatusCode.OK
    response.contentType()?.withoutParameters() shouldBe ContentType.parse("audio/flac")
    val served = fixtures.resolve("served-$taskId.flac")
    Files.write(served, response.bodyAsBytes())
    return flacSamples(served)
}

/**
 * Creates `stt.db` in [dataDir] migrated only to V7, holding task 1: COMPLETED, 800 ms long, with [chunk0]
 * and [chunk1] as its two DONE chunks' stored FLACs — the pre-item-44 state (ADR 0024).
 */
private fun seedLegacyTask(
    dataDir: Path,
    chunk0: Path,
    chunk1: Path,
    durationMs: Long,
) = legacyDatabase(
    dataDir,
    statements =
        listOf(
            "INSERT INTO tasks (id, original_filename, content_type, duration_ms, status, speakers_mode, created_at, finished_at) " +
                "VALUES (1, 'legacy.wav', 'audio/wav', $durationMs, 'COMPLETED', 'auto', 1, 1000)",
            "INSERT INTO audio (task_id, size_bytes, sha256) VALUES (1, 1, 'legacy-sha')",
            "INSERT INTO chunks (task_id, idx, start_ms, end_ms, status) VALUES (1, 0, 0, 300, 'DONE')",
            "INSERT INTO chunks (task_id, idx, start_ms, end_ms, status) VALUES (1, 1, 300, 800, 'DONE')",
        ),
    chunkFlacs = mapOf(1L to listOf(chunk0, chunk1)),
)

/**
 * Creates `stt.db` in [dataDir] migrated only to V7 with the CPU as the device: task 1 COMPLETED with its two DONE
 * chunks' FLACs [finished] (300 ms and 500 ms), and task 2 QUEUED with [unfinished] (1.5 s and 2 s) as its chunk
 * FLACs, chunk 0 DONE with one segment and chunk 1 PENDING.
 */
private fun seedLegacyQueue(
    dataDir: Path,
    finished: List<Path>,
    unfinished: List<Path>,
) = legacyDatabase(
    dataDir,
    statements =
        listOf(
            "UPDATE settings SET device = 'cpu' WHERE id = 1",
            "INSERT INTO tasks (id, original_filename, content_type, duration_ms, status, speakers_mode, created_at, finished_at) " +
                "VALUES (1, 'done.wav', 'audio/wav', 800, 'COMPLETED', 'off', 1, 1000)",
            "INSERT INTO tasks (id, original_filename, content_type, duration_ms, status, speakers_mode, created_at, queue_position) " +
                "VALUES (2, 'open.wav', 'audio/wav', 3500, 'QUEUED', 'off', 2, 0)",
            "INSERT INTO audio (task_id, size_bytes, sha256) VALUES (1, 1, 'done-sha')",
            "INSERT INTO audio (task_id, size_bytes, sha256) VALUES (2, 1, 'open-sha')",
            "INSERT INTO chunks (task_id, idx, start_ms, end_ms, status) VALUES (1, 0, 0, 300, 'DONE')",
            "INSERT INTO chunks (task_id, idx, start_ms, end_ms, status) VALUES (1, 1, 300, 800, 'DONE')",
            "INSERT INTO chunks (task_id, idx, start_ms, end_ms, status) VALUES (2, 0, 0, 1500, 'DONE')",
            "INSERT INTO chunks (task_id, idx, start_ms, end_ms, status) VALUES (2, 1, 1500, 3500, 'PENDING')",
            "INSERT INTO segments (task_id, chunk_idx, seq, start_ms, end_ms, text, speaker_id) VALUES (2, 0, 0, 0, 1000, ' hello ', NULL)",
        ),
    chunkFlacs = mapOf(1L to finished, 2L to unfinished),
)

/**
 * Creates `stt.db` in [dataDir] migrated only to V7 — the pre-item-44 state (ADR 0024) — runs [statements] on it, and
 * stores each task's chunk FLACs from [chunkFlacs] (task id → its chunks' files in `idx` order) in `chunk_audio`.
 */
internal fun legacyDatabase(
    dataDir: Path,
    statements: List<String>,
    chunkFlacs: Map<Long, List<Path>>,
) {
    val url = "jdbc:sqlite:${dataDir.resolve("stt.db")}"
    Flyway
        .configure()
        .dataSource(url, null, null)
        .target("7")
        .load()
        .migrate()
    DriverManager.getConnection(url).use { connection ->
        connection.createStatement().use { statement -> statements.forEach(statement::executeUpdate) }
        connection.prepareStatement("INSERT INTO chunk_audio (task_id, idx, flac) VALUES (?, ?, ?)").use { statement ->
            for ((taskId, files) in chunkFlacs) {
                files.forEachIndexed { idx, file ->
                    statement.setLong(1, taskId)
                    statement.setInt(2, idx)
                    statement.setBytes(3, Files.readAllBytes(file))
                    statement.executeUpdate()
                }
            }
        }
    }
}
