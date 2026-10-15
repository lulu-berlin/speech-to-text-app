package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication

class AudioRangeSpec :
    BehaviorSpec({

        given("a task with a stored canonical clip of 100 bytes (queue paused)") {
            `when`("requesting byte ranges of its audio") {
                then("206 is returned with the requested bytes and the right Content-Range") {
                    val dataDir = tempdir("stt-data").toPath()
                    val clip = testClip()
                    val id = taskWithTestClip(dataDir, clip)

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        // No range: the whole clip, as before.
                        val whole = client.get("/api/tasks/$id/audio")
                        whole.status shouldBe HttpStatusCode.OK
                        whole.contentType()?.withoutParameters() shouldBe ContentType.parse("audio/flac")
                        whole.bodyAsBytes() shouldBe clip.bytes

                        clip.bytes.size shouldBe 100
                        val size = clip.bytes.size

                        // Compose the expected slice from the clip itself and check headers by hand.
                        suspend fun range(
                            value: String,
                            start: Long,
                            end: Long,
                        ) {
                            val response = client.get("/api/tasks/$id/audio") { header(HttpHeaders.Range, value) }
                            response.status shouldBe HttpStatusCode.PartialContent
                            response.headers[HttpHeaders.ContentRange] shouldBe "bytes $start-$end/$size"
                            response.bodyAsBytes() shouldBe clip.bytes.copyOfRange(start.toInt(), (end + 1).toInt())
                        }

                        range("bytes=0-3", 0, 3)
                        // Crosses the boundary between the first and second stored rows (offset 50).
                        range("bytes=45-55", 45, 55)
                        range("bytes=90-", 90, 99)
                        range("bytes=-8", 92, 99)
                        range("bytes=0-0", 0, 0)

                        // Past the end: 416 with a Content-Range of "*"; nothing else served.
                        val unsatisfiable =
                            client.get("/api/tasks/$id/audio") {
                                header(HttpHeaders.Range, "bytes=200-300")
                            }
                        unsatisfiable.status shouldBe HttpStatusCode.RequestedRangeNotSatisfiable
                        unsatisfiable.headers[HttpHeaders.ContentRange] shouldBe "bytes */$size"
                    }
                }
            }
        }

        given("a task with a stored canonical clip of 100 bytes (queue paused), requested whole and by range") {
            `when`("its audio is served")
            {
                then("each response declares its length: 100 for the whole clip, 11 for bytes=45-55") {
                    val dataDir = tempdir("stt-data").toPath()
                    val clip = testClip()
                    val id = taskWithTestClip(dataDir, clip)

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        val whole = client.get("/api/tasks/$id/audio")
                        whole.headers[HttpHeaders.ContentLength] shouldBe "100"
                        whole.bodyAsBytes().size shouldBe 100

                        // bytes=45-55 is 55 − 45 + 1 = 11 bytes.
                        val part = client.get("/api/tasks/$id/audio") { header(HttpHeaders.Range, "bytes=45-55") }
                        part.headers[HttpHeaders.ContentLength] shouldBe "11"
                        part.bodyAsBytes().size shouldBe 11
                    }
                }
            }
        }
    })
