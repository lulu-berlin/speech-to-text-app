package stt.audio

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.nio.channels.FileChannel
import java.nio.file.Files

/**
 * Item 95: a run cancelled while it reads its canonical clip must still close the clip's channel — a plain
 * `withContext(Dispatchers.IO)` in the `finally` would not run once the job is gone.
 */
class CanonicalClipChannelSpec :
    BehaviorSpec({
        given("a canonical clip whose rows are being read when its run is cancelled") {
            `when`("the run's job is cancelled") {
                then("its file channel is closed") {
                    runTest {
                        val file = Files.createTempDirectory("stt-clip").resolve("canonical.flac")
                        Files.writeString(file, "not read")
                        lateinit var channel: FileChannel
                        val clip =
                            CanonicalClip(
                                layout = FlacLayout(ByteArray(0), IntArray(0)),
                                file = file,
                                openChannel = { path -> FileChannel.open(path).also { channel = it } },
                            )

                        val reading = CompletableDeferred<Unit>()
                        val job =
                            launch {
                                clip.useRows {
                                    reading.complete(Unit)
                                    awaitCancellation()
                                }
                            }
                        reading.await()
                        job.cancelAndJoin()

                        channel.isOpen shouldBe false
                    }
                }
            }
        }
    })
