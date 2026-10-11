package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import stt.Integration

/**
 * Item 29, fix 3: a COMPLETED task's work directory is gone (item 11 removes it), but its chunk FLACs
 * live in the database — so the real join must materialize its scratch somewhere. Regression: the joiner
 * wrote into the deleted work dir and `/api/tasks/{id}/audio` answered 500 for every finished task.
 */
class AudioJoinCompletedSpec :
    BehaviorSpec({
        tags(Integration)

        given("a COMPLETED task with stored chunk FLACs and no work directory") {
            `when`("its audio is requested") {
                then("200 audio/flac serves the joined FLAC, and a Range answers 206") {
                    val dataDir = tempdir("stt-join-completed").toPath()
                    val id = seedCompletedTaskWithChunks(dataDir)

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        startApplication()

                        // The join must recreate its scratch location on the fly: no work
                        // directory exists for the task, and the request still succeeds.
                        val whole = client.get("/api/tasks/$id/audio")
                        whole.status shouldBe HttpStatusCode.OK
                        whole.contentType()?.withoutParameters() shouldBe ContentType.parse("audio/flac")
                        val body = whole.bodyAsBytes()
                        body.size.shouldBeGreaterThan(100)
                        String(body, 0, 4, Charsets.UTF_8) shouldBe "fLaC"

                        // Browsers seek with a Range; the joined file answers it exactly.
                        val partial =
                            client.get("/api/tasks/$id/audio") {
                                header(HttpHeaders.Range, "bytes=0-99")
                            }
                        partial.status shouldBe HttpStatusCode.PartialContent
                        partial.headers[HttpHeaders.ContentRange] shouldBe "bytes 0-99/${body.size}"
                        partial.bodyAsBytes() shouldBe body.copyOfRange(0, 100)
                    }
                }
            }
        }
    })
