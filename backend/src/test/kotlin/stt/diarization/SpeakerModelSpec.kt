package stt.diarization

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.Integration
import stt.config.Config
import stt.config.TEST_DATA_DIR

class SpeakerModelSpec :
    BehaviorSpec({
        tags(Integration)

        given("the CAM++ model at \$STT_SPEAKER_MODEL") {
            `when`("it is loaded") {
                then("its metadata is read: output_dim 512, sample_rate 16000, normalize_samples true, global-mean") {
                    // STT_DATA_DIR is required by Config but unused here.
                    val path = Config.fromEnv(System.getenv() + ("STT_DATA_DIR" to TEST_DATA_DIR)).speakerModel

                    SpeakerModel.load(path).use { model ->
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
    })
