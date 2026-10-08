package stt.transcribe

import ch.qos.logback.classic.Level
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.withTimeout
import stt.Integration
import stt.db.Chunk
import stt.db.ChunkRepository
import stt.db.ChunkStatus
import stt.db.Database
import stt.db.Segment
import stt.db.SegmentRepository
import stt.db.TaskRepository
import stt.db.queuedTask
import stt.process.ProcessRunner
import stt.withCapturedWarnings
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

class ChunkTranscriptionSpec :
    BehaviorSpec({
        tags(Integration)

        given("a whisper-cli that writes one segment at 0–7 940 ms while printing -pp progress lines") {
            `when`("chunk 1 of the task is transcribed") {
                then("each -pp progress line is reported as the chunk fraction, then the chunk is persisted and its files deleted") {
                    val workDir = tempdir("stt-transcribe").toPath()
                    Database.open(tempdir("stt-db").toPath()).use { db ->
                        val chunks = ChunkRepository(db)
                        val segments = SegmentRepository(db)
                        val taskId = TaskRepository(db).insert(queuedTask("talk.wav", createdAt = 1L))
                        val chunk = Chunk(idx = 1, startMs = 30_000L, endMs = 60_000L, status = ChunkStatus.PENDING)
                        chunks.insertAll(taskId, listOf(chunk))

                        val flac = Files.writeString(workDir.resolve("chunk-1.flac"), "placeholder: the stand-in never reads it")
                        val prefix = workDir.resolve("chunk-1")
                        val json = workDir.resolve("chunk-1.json")
                        val output = """{"transcription": [{"offsets": {"from": 0, "to": 7940}, "text": " And so my fellow Americans"}]}"""
                        // Lines as printed by whisper-cli 1.9.2 (-pp), the percentage padded to width 3.
                        val stderr =
                            listOf(
                                "system_info: n_threads = 4 / 8 | WHISPER : COREML = 0 | OPENVINO = 0",
                                "whisper_print_progress_callback: progress =  27%",
                                "output_json: saving output to '$json'",
                                "whisper_print_progress_callback: progress = 100%",
                            )
                        val whisperCli = fakeWhisperCli(workDir, stderr, exitCode = 0, writes = json to output)
                        val transcription = ChunkTranscription(ChunkTranscriber(ProcessRunner(), whisperCli.toString()), segments)

                        val reported = mutableListOf<Double>()
                        withTimeout(30.seconds) {
                            transcription.transcribe(taskId, chunk, TEST_SETTINGS, flac, prefix, prompt = null) { reported += it }
                        }

                        reported.shouldBe(listOf(0.27, 1.0))
                        Files.exists(flac).shouldBeFalse()
                        Files.exists(json).shouldBeFalse()
                        chunks
                            .findByTask(taskId)
                            .single()
                            .status
                            .shouldBe(ChunkStatus.DONE)
                        // 30 000 + 0 and 30 000 + 7 940.
                        segments.findByTask(taskId).shouldBe(
                            listOf(
                                Segment(
                                    chunkIdx = 1,
                                    seq = 0,
                                    startMs = 30_000L,
                                    endMs = 37_940L,
                                    text = " And so my fellow Americans",
                                    speaker = null,
                                ),
                            ),
                        )
                    }
                }
            }
        }

        given("a whisper-cli that writes one segment while the chunk's FLAC is an undeletable directory") {
            `when`("chunk 1 of the task is transcribed") {
                then("the chunk is stored DONE with its segments despite the failed delete, and the failure is logged as a warning") {
                    val workDir = tempdir("stt-transcribe").toPath()
                    Database.open(tempdir("stt-db").toPath()).use { db ->
                        val chunks = ChunkRepository(db)
                        val segments = SegmentRepository(db)
                        val taskId = TaskRepository(db).insert(queuedTask("talk.wav", createdAt = 1L))
                        val chunk = Chunk(idx = 1, startMs = 30_000L, endMs = 60_000L, status = ChunkStatus.PENDING)
                        chunks.insertAll(taskId, listOf(chunk))

                        // A non-empty directory: Files.delete throws DirectoryNotEmptyException even when the tests run as root,
                        // unlike permission errors, which root bypasses.
                        val flacDir = workDir.resolve("chunk-1.flac")
                        Files.createDirectories(flacDir.resolve("inner"))
                        val prefix = workDir.resolve("chunk-1")
                        val json = workDir.resolve("chunk-1.json")
                        val output = """{"transcription": [{"offsets": {"from": 0, "to": 7940}, "text": " And so my fellow Americans"}]}"""
                        val whisperCli =
                            fakeWhisperCli(workDir, listOf("output_json: saving output to '$json'"), exitCode = 0, writes = json to output)
                        val transcription = ChunkTranscription(ChunkTranscriber(ProcessRunner(), whisperCli.toString()), segments)

                        withCapturedWarnings(ChunkTranscription::class.java.name) { events ->
                            withTimeout(30.seconds) {
                                transcription.transcribe(taskId, chunk, TEST_SETTINGS, flacDir, prefix, prompt = null)
                            }

                            chunks
                                .findByTask(taskId)
                                .single()
                                .status
                                .shouldBe(ChunkStatus.DONE)
                            segments.findByTask(taskId).shouldBe(
                                listOf(
                                    Segment(
                                        chunkIdx = 1,
                                        seq = 0,
                                        startMs = 30_000L,
                                        endMs = 37_940L,
                                        text = " And so my fellow Americans",
                                        speaker = null,
                                    ),
                                ),
                            )
                            // The JSON was deletable and is gone; the FLAC directory stays as a leftover.
                            Files.exists(json).shouldBeFalse()
                            Files.exists(flacDir).shouldBeTrue()
                            val warnings = events.filter { it.level == Level.WARN }
                            warnings.size.shouldBe(1)
                            warnings.single().formattedMessage.shouldContain("chunk-1.flac")
                            warnings.single().formattedMessage.shouldContain("DirectoryNotEmptyException")
                        }
                    }
                }
            }
        }
    })
