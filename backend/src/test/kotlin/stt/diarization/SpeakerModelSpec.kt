package stt.diarization

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.Integration
import stt.config.Config
import stt.config.TEST_DATA_DIR
import stt.config.realSpeakerModelPath

class SpeakerModelSpec :
    BehaviorSpec({
        tags(Integration)

        given("the CAM++ model at \$STT_SPEAKER_MODEL") {
            `when`("it is loaded") {
                then("its metadata is read: output_dim 512, sample_rate 16000, normalize_samples true, global-mean") {
                    SpeakerModel.load(realSpeakerModelPath()).use { model ->
                        // Expected values: the model file's metadata_props (ADR 0003, sherpa-onnx export).
                        model.metadata.shouldBe(
                            SpeakerModelMetadata(
                                outputDim = 512,
                                sampleRate = 16_000,
                                normalizeSamples = true,
                                featureNormalizeType = "global-mean",
                            ),
                        )
                    }
                }
            }
        }
        given("the CAM++ model at \$STT_SPEAKER_MODEL, with STT_THREADS set to 4 or unset") {
            `when`("loading the model") {
                then("the session loads with the configured intra-op threads; without STT_THREADS it uses ONNX Runtime's default") {
                    // SessionOptions has no read-back for the thread count in onnxruntime 1.30.0 (probed: javap shows
                    // setIntraOpNumThreads only), so the observable contract is a real session that accepts the
                    // configured options: both loads below must succeed.
                    val env = System.getenv() + ("STT_DATA_DIR" to TEST_DATA_DIR)
                    listOf(true, false).forEach { withThreads ->
                        val config = Config.fromEnv(if (withThreads) env + ("STT_THREADS" to "4") else env)

                        SpeakerModel.load(config.speakerModel, config.threads).use { model ->
                            model.metadata.shouldBe(
                                SpeakerModelMetadata(
                                    outputDim = 512,
                                    sampleRate = 16_000,
                                    normalizeSamples = true,
                                    featureNormalizeType = "global-mean",
                                ),
                            )
                        }
                    }
                }
            }
        }
    })
