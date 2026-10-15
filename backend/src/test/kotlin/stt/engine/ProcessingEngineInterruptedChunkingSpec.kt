package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import stt.audio.toCanonicalRows
import stt.db.AudioHeaderRepository
import stt.db.AudioSecondRepository
import stt.db.CanonicalAudio
import stt.db.TaskPhase
import stt.db.singleValue
import stt.db.storeHeader
import stt.db.storePlan
import stt.queue.TaskStatus
import kotlin.time.Duration.Companion.seconds

/**
 * Item 44, test-list item "(discovered) A task interrupted (canceled or killed) after its chunk plan is stored but
 * before its clip is, resumes by re-chunking and completes with playable audio; its original is deleted only once
 * the clip's header and rows are stored". CHUNKING stores the plan, the clip's header and its rows; an interruption
 * can land between any two of them. Every such task still holds its original, so the resumed run chunks it again,
 * stores the whole clip, and only then drops the original.
 */
class ProcessingEngineInterruptedChunkingSpec :
    BehaviorSpec({
        given("tasks interrupted mid-CHUNKING with a stored plan but no complete clip: two killed, one canceled and retried") {
            `when`("a new engine resumes them") {
                then("each is chunked again and completes with the whole clip stored, and only then loses its original") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val headers = AudioHeaderRepository(db)
                        val seconds = AudioSecondRepository(db)
                        val canonical = fixture.chunker.canonical

                        suspend fun interruptedInChunking(
                            filename: String,
                            position: Int,
                        ): Long {
                            val id = enqueueTask(fixture.tasks, filename, queuePosition = position, createdAt = position + 1L)
                            fixture.tasks.setRunningIfQueued(id, startedAt = 1_000L).shouldBeTrue()
                            fixture.tasks.setPhase(id, TaskPhase.CHUNKING)
                            storePlan(db, id, plannedWindows(chunkCount = 2))
                            return id
                        }

                        // Killed after the plan's commit, before the clip's header.
                        val afterPlan = interruptedInChunking("after-plan.wav", position = 0)
                        // Killed after the header's commit, before the clip's rows.
                        val afterHeader = interruptedInChunking("after-header.wav", position = 1)
                        storeHeader(db, afterHeader, canonical.header)
                        // Canceled after the plan's commit, then retried: queued again with the same stored state.
                        val canceled = interruptedInChunking("canceled.wav", position = 2)
                        fixture.tasks.cancelIfPossible(canceled, finishedAt = 2_000L).shouldBeTrue()
                        fixture.tasks.retryIfPossible(canceled).shouldBeTrue()
                        val interrupted = listOf(afterPlan, afterHeader, canceled)

                        fixture.engine.start()
                        pumpUntil(5.seconds) { interrupted.all { statusOf(fixture, it) == TaskStatus.COMPLETED } }

                        // Each was chunked again: the resumed run needed its original, so none was dropped early.
                        fixture.chunker.workDirs
                            .map(::taskIdOf)
                            .shouldContainAll(interrupted)
                        for (id in interrupted) {
                            statusOf(fixture, id) shouldBe TaskStatus.COMPLETED
                            headers.read(id)?.toList() shouldBe canonical.header.toList()
                            seconds.read(id).map(::comparable) shouldBe canonical.toCanonicalRows().map(::comparable)
                            singleValue(db, "SELECT count(*) FROM original_audio WHERE task_id = $id") shouldBe "0"
                        }
                    }
                }
            }
        }
    })

/** A stored row with its bytes as a list, so rows compare by content. */
private fun comparable(row: CanonicalAudio): Triple<Int, Int, List<Byte>> = Triple(row.second, row.frames, row.bytes.toList())
