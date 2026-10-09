package stt.diarization

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import stt.Integration
import stt.config.realSpeakerModelPath
import stt.db.Segment
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

class EmbeddingsSpec :
    BehaviorSpec({
        tags(Integration)

        given("a segment list and an empty PCM") {
            `when`("embedding") {
                then("every segment gets an all-zero embedding of the model's output dim, in segment order") {
                    // Oracle embed_windows: total <= 0 -> np.zeros((len(segments), 192)).
                    SpeakerModel.load(realSpeakerModelPath()).use { model ->
                        val segments = listOf(segment(0, 100), segment(4000, 4500), segment(9000, 10_000))

                        val embeddings = Embeddings(model).embed(FloatArray(0), segments)

                        embeddings.size.shouldBe(3)
                        embeddings.forEach { embedding ->
                            embedding.size.shouldBe(512)
                            embedding.forEach { sample -> sample.shouldBe(0f) }
                        }
                    }
                }
            }
        }
        given("a PCM and segments at different positions") {
            `when`("embedding them all at once and each one alone") {
                then("each position in the result holds the embedding of the matching segment, in order") {
                    // Ported windows stay in segment order: a length-sorting batching (as in oracle embed_windows)
                    // would reorder these three segments, whose window lengths differ (1200 ms, 1800 ms, 1200 ms).
                    val pcm = FloatArray(192_000) // 12 s
                    val tone = { frequency: Double, fromMs: Long, toMs: Long ->
                        for (i in (fromMs * 16) until (toMs * 16)) pcm[i.toInt()] = (0.05 * sin(2 * PI * frequency * i / 16_000)).toFloat()
                    }
                    tone(440.0, 0, 1200)
                    tone(3000.0, 3000, 4800)
                    tone(440.0, 10_000, 11_200)

                    SpeakerModel.load(realSpeakerModelPath()).use { model ->
                        val embedder = Embeddings(model)
                        val segments = listOf(segment(0, 1200), segment(3000, 4800), segment(10_000, 11_200))

                        val embeddings = embedder.embed(pcm, segments)

                        embeddings.size.shouldBe(3)
                        embeddings.indices.forEach { index ->
                            val alone = embedder.embed(pcm, listOf(segments[index]))[0]
                            embeddings[index].indices.forEach { component ->
                                embeddings[index][component].shouldBe(alone[component].plusOrMinus(1e-4f))
                            }
                        }
                        // Sanity: the 3 kHz middle segment is not the same vector as the 440 Hz first one.
                        dotInFloat(embeddings[0], embeddings[1]).shouldBeLessThan(0.9999f)
                    }
                }
            }
        }
        given("the real model, two segments, and a pause gate") {
            `when`("pausing after the first window") {
                then("embedding suspends between windows and resumes with the next window") {
                    // ADR 0004: in-JVM phases pause cooperatively through a pause gate checked between windows.
                    val pcm = tone440(64_000)
                    SpeakerModel.load(realSpeakerModelPath()).use { model ->
                        val embedder = Embeddings(model)
                        val gate = PauseGate()
                        val job =
                            async {
                                embedder.embed(pcm, listOf(segment(0, 1200), segment(30_000, 31_200)), gate) { done, _ ->
                                    if (done == 1) gate.pause()
                                }
                            }

                        val finishedWhilePaused = withTimeoutOrNull(500.milliseconds) { job.await() }
                        finishedWhilePaused.shouldBe(null) // still suspended at the checkpoint between the windows

                        gate.resume()

                        job.await().size.shouldBe(2)
                    }
                }
            }
        }
        given("the real model and three segments, without and with a pause gate") {
            `when`("the embedding coroutine is cancelled as window 1 finishes") {
                then("it stops at the next window boundary: only window 1 is done and the job ends cancelled") {
                    // Spec: pause gate and cancellation are checked between windows (ADR 0004); inference blocks, so a cancel
                    // must land at the next window boundary instead of after the last window. Cancelling inside the
                    // window-1 callback makes "the next boundary" exact: no race with window 2's inference.
                    val pcm = tone440(64_000)
                    val segments = listOf(segment(0, 1200), segment(1200, 2400), segment(2400, 3600))
                    SpeakerModel.load(realSpeakerModelPath()).use { model ->
                        val embedder = Embeddings(model)
                        for (gate in listOf(null, PauseGate())) {
                            val windowsDone = mutableListOf<Int>()
                            lateinit var job: Deferred<Array<FloatArray>>
                            job =
                                async(start = CoroutineStart.LAZY) {
                                    embedder.embed(pcm, segments, gate) { done, _ ->
                                        windowsDone.add(done)
                                        if (done == 1) job.cancel()
                                    }
                                }
                            job.start()

                            withClue(if (gate == null) "without a pause gate" else "with a pause gate") {
                                shouldThrow<CancellationException> { job.await() }
                                windowsDone.shouldBe(listOf(1))
                            }
                        }
                    }
                }
            }
        }
        given("three LibriSpeech test-clean clips: two of speaker 1089 from different chapters, one of speaker 1188") {
            `when`("each whole clip is embedded") {
                then("the two clips of speaker 1089 are more similar to each other than either is to the clip of speaker 1188") {
                    // Spec (item 08 acceptance): same-speaker cosine > different-speaker cosine. Embeddings are
                    // L2-normalized, so the cosine is the dot product. Clips: docs/attributions.md (CC BY 4.0, ADR 0026).
                    val pcms = decodeLibrispeech(tempdir("librispeech").toPath(), LIBRISPEECH_IDS)
                    SpeakerModel.load(realSpeakerModelPath()).use { model ->
                        val embedder = Embeddings(model)
                        val (speakerA1, speakerA2, speakerB) = pcms.values.map { pcm -> embedder.embed(pcm, listOf(wholeClip(pcm)))[0] }

                        val same = dotInFloat(speakerA1, speakerA2)
                        val different = listOf(dotInFloat(speakerA1, speakerB), dotInFloat(speakerA2, speakerB))

                        withClue("same-speaker cosine $same, different-speaker cosines $different") {
                            different.forEach { same.shouldBeGreaterThan(it) }
                        }
                    }
                }
            }
        }
        given("a 600 ms excerpt starting 2 s into each LibriSpeech clip, zero-padded to the 2 s window, and the three whole clips") {
            `when`("the excerpts and the whole clips are embedded") {
                then("each excerpt is more similar to every whole clip of its own speaker than to the whole clip of the other speaker") {
                    // Review round 1, finding 1: CAM++ (normalize_samples = 1) takes samples in [-1, 1] unscaled, as
                    // sherpa-onnx does. A wrong scale shifts every log-mel value of frames with sound but leaves the
                    // zero padding at the log floor, so global-mean normalization hides it only for unpadded windows.
                    val pcms = decodeLibrispeech(tempdir("librispeech").toPath(), LIBRISPEECH_IDS)
                    SpeakerModel.load(realSpeakerModelPath()).use { model ->
                        val embedder = Embeddings(model)
                        val whole = pcms.mapValues { (_, pcm) -> embedder.embed(pcm, listOf(wholeClip(pcm)))[0] }
                        val excerpts = pcms.mapValues { (_, pcm) -> embedder.embed(pcm, listOf(segment(2000, 2600)))[0] }

                        val speaker = { id: String -> id.substringBefore('-') }
                        for ((id, excerpt) in excerpts) {
                            val own = whole.filterKeys { speaker(it) == speaker(id) }.values.map { dotInFloat(excerpt, it) }
                            val other = whole.filterKeys { speaker(it) != speaker(id) }.values.map { dotInFloat(excerpt, it) }
                            withClue("excerpt of $id: own-speaker cosines $own, other-speaker cosines $other") {
                                own.min().shouldBeGreaterThan(other.max())
                            }
                        }
                    }
                }
            }
        }
        given("the window of 1089-134686-0002 at 2000–2600 ms, zero-padded with the segment centered in 32 000 samples") {
            `when`("it is embedded") {
                then("the embedding matches the sherpa-onnx reference: cosine >= 0.999") {
                    // Reference: sherpa-onnx SpeakerEmbeddingExtractor (the runtime this CAM++ export targets), same model
                    // (ADR 0003 sha256), same window (clip samples 32 000–41 600 at offset 11 200), samples int16 / 32768,
                    // run by the user outside the repository (ADR 0002; Approval log 2026-10-09, review round 1).
                    val pcm = decodeLibrispeech(tempdir("librispeech").toPath(), LIBRISPEECH_IDS).getValue("1089-134686-0002")
                    // Checksums printed by the reference run: the clip and segment are the ones the reference was computed on.
                    pcm.size.shouldBe(106_000)
                    val segmentInt16 = (32_000 until 41_600).map { (pcm[it] * 32768f).roundToInt() }
                    segmentInt16.sum().shouldBe(-38_678)
                    segmentInt16.sumOf { abs(it) }.shouldBe(8_935_266)

                    SpeakerModel.load(realSpeakerModelPath()).use { model ->
                        val embedding = Embeddings(model).embed(pcm, listOf(segment(2000, 2600)))[0]

                        SHERPA_ONNX_REFERENCE.size.shouldBe(512)
                        // sherpa-onnx returns the raw embedding; ours has unit length, so cosine = dot / |reference|.
                        val cosine =
                            dotInFloat(embedding, SHERPA_ONNX_REFERENCE) / sqrt(dotInFloat(SHERPA_ONNX_REFERENCE, SHERPA_ONNX_REFERENCE))
                        cosine.shouldBeGreaterThanOrEqual(0.999f)
                    }
                }
            }
        }
    })

/** The segment covering all of [pcm], in whole milliseconds. */
private fun wholeClip(pcm: FloatArray): Segment = segment(0, pcm.size / 16L)

/** [sampleCount] samples of a quiet (amplitude 0.05) 440 Hz tone at 16 kHz. */
private fun tone440(sampleCount: Int): FloatArray = FloatArray(sampleCount) { i -> (0.05 * sin(2 * PI * 440 * i / 16_000)).toFloat() }

/** Item 08's LibriSpeech utterances (docs/attributions.md): two of speaker 1089 from different chapters, one of speaker 1188. */
private val LIBRISPEECH_IDS = listOf("1089-134686-0002", "1089-134691-0023", "1188-133604-0025")

/**
 * sherpa-onnx `SpeakerEmbeddingExtractor` embedding (raw, not normalized) of 1089-134686-0002 at 2000–2600 ms, zero-padded
 * and centered in 32 000 samples, with the CAM++ model of ADR 0003; computed by the user outside the repository and printed
 * with 8 significant decimals, 8 values per line.
 */
private val SHERPA_ONNX_REFERENCE =
    floats(
        """
        -1.77426875e-01 -7.58661449e-01 -3.19187790e-02 -2.12781262e+00 -5.15670419e-01 1.68459964e+00 -8.61301243e-01 -1.82676208e+00
        -5.43776155e-02 1.48212266e+00 -7.76896775e-01 1.91332161e-01 -2.28483617e-01 1.03644764e+00 1.07743001e+00 6.98013067e-01
        3.01067412e-01 1.27453363e+00 9.18103695e-01 4.88628209e-01 6.79704547e-02 -7.94096470e-01 -3.40235114e-01 -3.33418548e-01
        -9.05334711e-01 1.39135218e+00 1.36327374e+00 -1.73029590e+00 8.76194358e-01 1.26732588e+00 -8.54246318e-01 9.38208818e-01
        -3.79885584e-01 8.70427668e-01 5.07069647e-01 -1.05077517e+00 -8.34087014e-01 -4.30617988e-01 -8.21917057e-02 -1.03633428e+00
        1.92658305e-02 -1.26228511e+00 -1.79622099e-01 -1.56023073e+00 -1.59394538e+00 -6.33717775e-01 -2.21313715e-01 -2.32475686e+00
        -1.17377210e+00 -4.62712198e-01 4.99588192e-01 -5.89887679e-01 -1.60996699e+00 -5.49781561e-01 9.93314981e-02 -9.16313529e-01
        -4.54807729e-02 2.59882855e+00 8.49671006e-01 -3.74237418e-01 -4.49436605e-01 -7.13824630e-02 -5.85444510e-01 -8.51574421e-01
        7.47821808e-01 1.18135762e+00 -3.14945757e-01 -6.66024566e-01 4.23287809e-01 4.61480618e-01 -8.07500407e-02 6.67500496e-01
        1.48945063e-01 -2.31461644e-01 -5.42161942e-01 -1.02587724e+00 -2.79208332e-01 5.68060637e-01 8.59296560e-01 4.54267859e-03
        3.76851976e-01 -2.79484391e-01 1.85619518e-01 6.69581532e-01 6.20269537e-01 -3.22115362e-01 2.49004424e-01 1.20730567e+00
        -5.49288630e-01 2.15208840e+00 -1.93161115e-01 7.76623964e-01 1.80304003e+00 3.73919427e-01 -4.87748384e-01 1.47253859e+00
        -1.02039576e+00 2.11256671e+00 -1.27212822e+00 1.19383126e-01 9.26587880e-01 1.93518698e-01 7.14929402e-01 -1.35984814e+00
        -1.68865585e+00 2.20115185e+00 -2.56072819e-01 7.07475066e-01 -6.00670338e-01 -1.58194387e+00 6.86041832e-01 -1.65983832e+00
        -1.10736918e+00 -3.99076313e-01 -5.67797363e-01 9.83831227e-01 3.46127450e-01 -5.37501454e-01 1.45187020e-01 1.32596588e+00
        8.65797997e-01 -3.59428257e-01 -1.51041985e+00 4.24105525e-02 -2.23298097e+00 1.59156775e+00 3.21802497e-01 -2.35385346e+00
        -7.47066498e-01 5.37674069e-01 -7.31491864e-01 -3.14516217e-01 9.40699935e-01 1.68223846e+00 -6.97056949e-01 2.29977798e+00
        -6.81463778e-01 -4.09284621e-01 1.58368897e+00 5.09205520e-01 -5.26376247e-01 8.97434294e-01 7.01233864e-01 4.28845108e-01
        -1.45147145e-01 -2.75023270e+00 -8.60535860e-01 -1.34119964e+00 -2.26165861e-01 2.25783139e-01 4.73840386e-01 -3.40029001e-01
        1.68413544e+00 -7.86609054e-01 -4.35146838e-01 -1.02267122e+00 1.63860691e+00 -2.32336617e+00 2.52986014e-01 8.80069494e-01
        -9.25107956e-01 -3.17548454e-01 -1.82134843e+00 -9.24511790e-01 -1.99607646e+00 -2.85573900e-01 7.77450740e-01 4.73429233e-01
        4.53824282e-01 -6.43495500e-01 -4.83493626e-01 1.87660426e-01 -2.10493684e+00 1.52906132e+00 3.23922515e-01 9.85243320e-01
        -7.46040285e-01 1.74311268e+00 1.53225112e+00 1.21462375e-01 -1.59757102e+00 -7.79140115e-01 -7.24081397e-01 1.93386281e+00
        1.06177604e+00 -8.40817690e-01 -5.17876208e-01 5.77118814e-01 -2.59140015e+00 3.26932669e-02 4.98146534e-01 -8.90071213e-01
        1.87225032e+00 3.74713093e-01 -4.40576226e-01 -9.20260072e-01 -2.53734827e-01 -2.50624657e+00 -4.12303746e-01 -1.34271967e+00
        6.55778348e-01 -4.12081033e-01 1.13129234e+00 -5.80934405e-01 -2.04942679e+00 1.01282418e-01 -2.66377866e-01 5.14032423e-01
        7.54308999e-02 -6.15388155e-01 9.74544883e-01 -2.24139214e-01 3.11351418e-01 -8.35317373e-02 -5.89907765e-01 -2.27933258e-01
        -5.75098515e-01 -1.88781059e+00 4.58624035e-01 1.11698449e+00 -1.97757494e+00 9.13920045e-01 5.86860418e-01 8.91023278e-01
        -5.25286794e-03 5.81453323e-01 2.19375229e+00 8.57363701e-01 1.07561111e-01 5.95001727e-02 4.63676453e-03 -3.01854342e-01
        -3.14949602e-01 -1.00278544e+00 5.63760698e-01 -1.97946936e-01 7.38717616e-01 -6.15629852e-01 -2.54927352e-02 1.24198091e+00
        1.62204146e+00 4.93575186e-01 8.55005443e-01 2.09865284e+00 9.66512799e-01 -3.09615076e-01 1.00047195e+00 -1.81647491e+00
        1.61197484e-01 4.26464945e-01 7.71367490e-01 2.19455436e-02 1.00465393e+00 2.88394392e-01 -4.51356530e-01 7.63339102e-02
        5.02307117e-02 -1.20242238e-02 4.43485260e-01 2.56881565e-01 1.18209183e-01 2.73629844e-01 -1.43200755e-02 -3.76542285e-02
        2.62421966e-01 -1.93412378e-01 6.99695587e-01 1.08977544e+00 -1.38711238e+00 -1.97691083e-01 -1.98612630e+00 5.13756871e-01
        5.84984660e-01 2.31047515e-02 4.80996430e-01 -2.58079886e-01 -9.31398153e-01 8.51631165e-03 1.63068473e-01 4.11984563e-01
        1.17515147e+00 -4.87943113e-01 -6.34867609e-01 1.71738714e-02 -1.23321462e+00 -2.68361330e-01 -1.42811906e+00 3.36716771e-02
        2.52667737e+00 5.42544648e-02 -2.61446714e-01 -7.84444213e-01 1.67551970e+00 1.09878612e+00 5.27334034e-01 6.09470248e-01
        1.13088930e+00 1.52215064e+00 5.45907378e-01 -1.82474899e+00 8.78597856e-01 1.46127984e-01 -1.42482984e+00 -1.61930466e+00
        -1.20015514e+00 -4.37194824e-01 -1.42738962e+00 -7.27615416e-01 -3.39427531e-01 -1.16528392e+00 -2.30741477e+00 9.28255677e-01
        -7.83297837e-01 1.91483521e+00 4.34718877e-01 1.95009577e+00 1.03622818e+00 -4.52504426e-01 9.32007670e-01 1.27043021e+00
        6.92034900e-01 -1.15926945e+00 4.35863078e-01 -5.91165066e-01 -1.49820757e+00 -2.09690142e+00 -5.01687706e-01 2.82711935e+00
        5.77779055e-01 9.91773129e-01 -1.12067938e+00 -4.14594829e-01 -5.23964167e-02 -5.11419654e-01 1.67724800e+00 1.19990087e+00
        -3.46675366e-01 -1.08859599e+00 1.16541505e+00 6.44626617e-01 1.89922929e-01 4.62363690e-01 2.31690407e-02 -1.44323766e-01
        -1.22536230e+00 -3.53933036e-01 7.46537685e-01 -8.71546149e-01 5.21035373e-01 -2.68359333e-01 -2.36781216e+00 6.83242679e-02
        -3.52326691e-01 6.61443055e-01 -1.15908158e+00 -4.41473246e-01 1.07910025e+00 3.13059747e-01 -9.47532356e-01 -2.55785918e+00
        4.44034934e-01 -2.23350286e-01 -1.30364180e-01 2.89979219e-01 1.13761437e+00 1.33974731e-01 -5.29877067e-01 2.32259393e-01
        1.75636721e+00 1.35806179e+00 -3.48489881e-02 1.59144878e+00 -4.75239813e-01 4.96141762e-02 -1.18874300e+00 9.07139659e-01
        -4.16382775e-02 -1.83879912e-01 5.09850323e-01 7.00271130e-02 -1.73680246e-01 1.36162758e+00 1.59070766e+00 -3.31167579e-01
        -5.74340641e-01 6.73314929e-01 2.04820776e+00 9.64974284e-01 -5.37423432e-01 -1.06773567e+00 -4.25556362e-01 -3.38162512e-01
        7.61952400e-01 1.16389401e-01 1.22105658e+00 -1.23458278e+00 7.40948379e-01 -5.55411726e-02 2.73166090e-01 -1.35699391e+00
        -1.27678704e+00 -6.31832838e-01 -1.34878159e+00 -1.03744423e+00 -7.39213228e-01 5.99422753e-01 1.43812370e+00 9.36675549e-01
        -1.56776071e+00 1.19833022e-01 1.59333968e+00 -7.20421672e-01 2.59321988e-01 8.97711754e-01 7.55155683e-01 3.53221536e-01
        -1.66125870e+00 -2.46399403e-01 2.22104216e+00 6.49426639e-01 -2.47973871e+00 -1.58039957e-01 1.55658567e+00 2.28102803e-01
        -9.34320927e-01 4.42997068e-01 -1.16555941e+00 -1.96943092e+00 6.17781639e-01 -6.58749759e-01 -1.17841196e+00 -1.97392493e-01
        3.57491255e-01 -1.39306736e+00 1.11456728e+00 1.55930430e-01 -7.22281456e-01 1.51945204e-01 1.96124768e+00 -5.02150238e-01
        -1.82273281e+00 7.47893929e-01 6.85998797e-01 1.59093881e+00 -2.50304580e-01 1.12802577e+00 2.03923047e-01 7.36311078e-02
        2.22670579e+00 -7.74359226e-01 -1.11173129e+00 -1.07889950e+00 -2.59635806e-01 -1.95726931e-01 -1.68426144e+00 -9.96829987e-01
        2.48720288e-01 4.56800163e-02 -1.54304659e+00 -1.88204765e+00 4.24519598e-01 -6.43854737e-01 1.42189336e+00 1.35958290e+00
        -5.47343791e-01 4.20309544e-01 7.16027141e-01 -6.18388534e-01 -7.91191339e-01 -1.21855736e+00 1.82061708e+00 1.22394133e+00
        5.75946212e-01 1.09288144e+00 1.95518470e+00 -4.99428511e-02 6.90799952e-01 -3.25523973e-01 3.36211503e-01 2.75506675e-01
        -1.36996657e-02 -6.06147468e-01 -1.68312216e+00 2.74872422e-01 -5.95911741e-01 -4.17899728e-01 6.56013966e-01 -5.08939564e-01
        1.57151508e+00 6.77367687e-01 -9.81657445e-01 -8.85691166e-01 2.43234456e-01 -3.03983986e-02 1.24178922e+00 -9.16451156e-01
        3.59502703e-01 -8.69744539e-01 -1.00115299e-01 8.21171165e-01 -2.52328897e+00 7.18231797e-02 9.29225743e-01 1.33160615e+00
        6.44399405e-01 2.37489939e-01 -1.00950956e+00 2.26396561e+00 -1.11419272e+00 1.13536346e+00 -5.78561485e-01 -1.22274375e+00
        """,
    )
