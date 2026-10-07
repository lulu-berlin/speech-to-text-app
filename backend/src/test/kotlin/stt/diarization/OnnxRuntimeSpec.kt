package stt.diarization

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtProvider
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain

class OnnxRuntimeSpec :
    BehaviorSpec({
        given("the JVM inside the devenv shell") {
            `when`("the ONNX Runtime environment is created") {
                then("the native library loads and offers the CPU execution provider") {
                    OrtEnvironment.getEnvironment()

                    OrtEnvironment.getAvailableProviders().shouldContain(OrtProvider.CPU)
                }
            }
        }
    })
