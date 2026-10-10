package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.ranges.shouldBeIn
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import stt.Integration
import stt.config.Config
import stt.config.Device
import stt.config.realSpeakerModelPath
import stt.db.Database
import stt.db.TaskRepository
import stt.db.TaskWorkDirectories
import stt.db.queuedTask
import stt.transcribe.transcriptFixtures
import java.io.ByteArrayInputStream
import java.nio.file.Files

/**
 * `task-progress` only exists while a real transcription runs (the engine emits it from whisper-cli's
 * `-pp` output), so it cannot be produced through commands in a unit test; this spec runs the real pipeline
 * (tiny whisper + CAM++) and watches the SSE stream for the progress events and the COMPLETED update.
 */
class EventsIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

        given("a queued task on the real pipeline") {
            `when`("an SSE client drains the events during the run") {
                then("a snapshot arrives first, then task-progress and finally a COMPLETED task-updated") {
                    val (whisperModel, jfk) = transcriptFixtures()
                    val dataDir = tempdir("stt-events").toPath()
                    Database.open(dataDir).use { db ->
                        TaskRepository(db, TaskWorkDirectories(dataDir)).createFromUpload(
                            queuedTask("jfk.wav", createdAt = 1).copy(queuePosition = 0),
                            ByteArrayInputStream(Files.readAllBytes(jfk)),
                        )
                    }

                    SseServer({ sttApp(Config(dataDir, realSpeakerModelPath(), whisperModel, device = Device.Cpu)) })
                        .use { server ->
                            val events = server.connectEvents()

                            val (snapshotName, _) = events.readEvent()
                            snapshotName shouldBe "snapshot"

                            // Drain until the task progressed and then completed; the per-line deadline in
                            // SseTestReader plus this check gives the whole run a bound.
                            var sawProgress = false
                            var completed = false
                            val deadline = System.currentTimeMillis() + 3 * 60_000L
                            while (!(sawProgress && completed)) {
                                check(System.currentTimeMillis() < deadline) { "timed out before the task progressed and completed" }
                                val (name, data) = events.readEvent()
                                val json = Json.parseToJsonElement(data).jsonObject
                                when (name) {
                                    "task-progress" -> {
                                        sawProgress = true
                                        json
                                            .getValue("transcriptionProgress")
                                            .jsonPrimitive.content
                                            .toDouble()
                                            .shouldBeIn(0.0..1.0)
                                    }

                                    "task-updated" -> {
                                        if (json.getValue("status").jsonPrimitive.content == "COMPLETED") completed = true
                                    }
                                }
                            }
                        }
                }
            }
        }
    })
