package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * Roadmap item 66, test-list item D7: the service reads the same frontend registry the tray and `stt-app` read, so the
 * Settings page can offer the installed frontends as the default. A file it cannot use is skipped — the next cycle
 * pins the warning itself — and never hides the valid ones.
 */
class FrontendsSpec :
    BehaviorSpec({
        given("a registry with one browser descriptor and one file that does not parse") {
            `when`("GET /api/frontends") {
                then("it answers the browser frontend only") {
                    val dir = registryDir()
                    verifyCleanStop {
                        application { sttApp(testConfig(), frontendsDir = dir) }
                        val response = client.get("/api/frontends")
                        response.status shouldBe HttpStatusCode.OK
                        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        body shouldBe apiExample("frontends.json")
                    }
                }
            }
        }
    })

/** A `frontends` directory holding a valid `browser` descriptor and a truncated file. */
private fun registryDir(): Path {
    val dir = Files.createTempDirectory("stt-frontends").resolve("frontends")
    Files.createDirectories(dir)
    Files.writeString(
        dir.resolve("browser.json"),
        """{"version":1,"id":"browser","kind":"browser","name":"Browser","url":"/"}""",
    )
    Files.writeString(dir.resolve("broken.json"), """{"version":1,"id":""")
    return dir
}
