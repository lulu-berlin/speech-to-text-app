package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class ProcessingEngineProgressSpec :
    BehaviorSpec({
        given("a two-chunk task whose speakers are off") {
            `when`("the engine transcribes it") {
                then("it reports progress weighted by chunk duration as task-progress and ends at 1") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, fixture.workDirectories, "task.wav", queuePosition = 0, createdAt = 1)
                        fixture.chunker.chunkCount = 2
                        fixture.transcriber.progressScript = listOf(0.0, 0.5, 1.0)

                        fixture.engine.start()
                        fixture.completed.receive() shouldBe task

                        val reports = drain(fixture.progress)
                        reports.map { it.id }.distinct() shouldBe listOf(task)
                        reports.map { it.transcriptionProgress } shouldBe listOf(0.0, 0.25, 0.5, 0.5, 0.75, 1.0)
                        reports.all { it.diarizationStage == null && it.diarizationProgress == null }.shouldBeTrue()
                        fixture.tasks
                            .find(task)
                            ?.task
                            ?.progress shouldBe 1.0
                    }
                }
            }
        }
    })
