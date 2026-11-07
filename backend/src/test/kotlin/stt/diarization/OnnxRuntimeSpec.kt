package stt.diarization

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtProvider
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class OnnxRuntimeSpec :
    BehaviorSpec({
        given("the JVM inside the devenv shell") {
            `when`("the ONNX Runtime environment is created") {
                then("the native library loads and offers the CPU execution provider") {
                    OrtEnvironment.getEnvironment()

                    OrtEnvironment.getAvailableProviders().shouldContain(OrtProvider.CPU)
                }
                then("the native library is the catalog pin, 1.31.1") {
                    // Must equal the catalog pin (ADR 0042 force) — update both together. 1.29.0/1.30.0
                    // segfault on one machine and 1.26.0-1.28.0 degrade CAM++ embeddings (item 30).
                    OrtEnvironment.getEnvironment().version.shouldBe("1.31.1")
                }
            }
        }
    })
