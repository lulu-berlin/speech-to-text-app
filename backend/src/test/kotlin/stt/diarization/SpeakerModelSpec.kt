package stt.diarization

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.Integration
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
        given("the CAM++ model at \$STT_SPEAKER_MODEL, with the settings' thread count 4 or unset") {
            `when`("loading the model") {
                then("the session loads with 4 intra-op threads, or with ONNX Runtime's default when unset") {
                    // SessionOptions has no read-back for the thread count in onnxruntime's Java API (probed:
                    // javap shows setIntraOpNumThreads only), so the observable contract is a real session that
                    // accepts the options: both loads below must succeed.
                    listOf(4, null).forEach { threads ->
                        SpeakerModel.load(realSpeakerModelPath(), threads).use { model ->
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
