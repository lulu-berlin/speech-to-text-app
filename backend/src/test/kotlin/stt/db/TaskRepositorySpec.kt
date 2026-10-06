package stt.db

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class TaskRepositorySpec :
    BehaviorSpec({

        given("a task with every field set and a task with every nullable field null") {
            `when`("both are saved through the repository and read back by id") {
                then("each reads back with identical fields") {
                    runTest {
                        val dataDir = tempdir("stt-db").toPath()
                        Database.open(dataDir).use { db ->
                            val tasks = TaskRepository(db)
                            val full =
                                Task(
                                    originalFilename = "interview.m4a",
                                    contentType = "audio/mp4",
                                    durationMs = 61_000L,
                                    status = TaskStatus.RUNNING,
                                    phase = TaskPhase.TRANSCRIBING,
                                    queuePosition = 3,
                                    language = "de",
                                    speakersMode = SpeakersMode.FIXED,
                                    speakersCount = 2,
                                    maxSpeakers = 4,
                                    model = "base.en",
                                    progress = 0.25,
                                    error = "previous attempt failed",
                                    createdAt = 1_700_000_000_000L,
                                    startedAt = 1_700_000_001_000L,
                                    finishedAt = 1_700_000_002_000L,
                                )
                            val sparse =
                                Task(
                                    originalFilename = "memo.wav",
                                    contentType = "audio/wav",
                                    durationMs = null,
                                    status = TaskStatus.QUEUED,
                                    phase = null,
                                    queuePosition = null,
                                    language = "en",
                                    speakersMode = SpeakersMode.AUTO,
                                    speakersCount = null,
                                    maxSpeakers = 8,
                                    model = "large-v3-turbo-q5_0",
                                    progress = 0.0,
                                    error = null,
                                    createdAt = 1_700_000_003_000L,
                                    startedAt = null,
                                    finishedAt = null,
                                )

                            val fullId = tasks.insert(full)
                            val sparseId = tasks.insert(sparse)

                            tasks.find(fullId).shouldBe(full)
                            tasks.find(sparseId).shouldBe(sparse)
                        }
                    }
                }
            }
        }
    })
