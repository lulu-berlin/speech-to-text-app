package stt.service

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Roadmap item 66, test-list D2 and D11: without `STT_CONFIG_DIR` and `STT_RUNTIME_DIR` the service keeps its state
 * where each OS puts a per-user app's configuration and runtime files (the Evidence table), and the overrides win
 * when set. The cases live in `docs/service-dirs.json`, which the Rust client's tests read too, so the service and
 * its clients always look in the same place.
 */
class ServiceDirsSpec :
    BehaviorSpec({
        given("every case of the shared directory table") {
            `when`("the service resolves its directories") {
                then("it names the case's config and runtime directories, and warns only on a fallback") {
                    for (case in directoryCases()) {
                        val name = case.getValue("name").jsonPrimitive.content
                        val env =
                            case.getValue("env").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }
                        val resolved = ServiceDirs.resolve(env, platformOf(case.getValue("os").jsonPrimitive.content))

                        withClue(name) {
                            resolved.dirs shouldBe
                                ServiceDirs(
                                    Path.of(case.getValue("configDir").jsonPrimitive.content),
                                    Path.of(case.getValue("runtimeDir").jsonPrimitive.content),
                                )
                            (resolved.warning != null) shouldBe case.getValue("warns").jsonPrimitive.boolean
                        }
                    }
                }
            }
        }
    })

private fun directoryCases() =
    Json
        .parseToJsonElement(sharedTable().readText())
        .jsonObject
        .getValue("cases")
        .jsonArray
        .map { it.jsonObject }

/** `docs/service-dirs.json`, wherever the test JVM's working directory sits in the repository. */
private fun sharedTable(): Path =
    listOf(Path.of("../docs/service-dirs.json"), Path.of("docs/service-dirs.json")).firstOrNull(Files::exists)
        ?: error("docs/service-dirs.json not found; working directory is ${Path.of("").toAbsolutePath()}")

private fun platformOf(os: String): Platform =
    when (os) {
        "linux" -> Platform.Linux
        "macos" -> Platform.MacOs
        "windows" -> Platform.Windows
        else -> error("unknown os $os in docs/service-dirs.json")
    }
