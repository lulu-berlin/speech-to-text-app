package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import stt.db.AudioRepository
import stt.db.Database
import stt.db.FailingAfter
import stt.db.GeneratedBytes
import stt.db.MIB
import stt.db.QueueStateRepository
import stt.db.rowCount
import stt.db.sha256Hex
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Item 44, test-list item "The upload is stored in the database as it arrives": no spool file appears anywhere,
 * neither in the data directory nor in the OS temp dir, during the upload, after success, after a mid-body
 * failure, or after a 400 rejection. The stored original read back from the database equals the uploaded bytes.
 * Every body is several MiB, larger than what the test client buffers ahead, so the disk checks taken while the
 * client delivers a body run while the server is consuming it.
 */
class UploadStoredInDatabaseSpec :
    BehaviorSpec({

        given("a data directory with the queue paused") {
            `when`("an 8 MiB upload succeeds, a body breaks after 6 MiB, and a 6 MiB .txt upload is rejected") {
                then("no file appears outside the database at any point, and the stored original equals the upload") {
                    val dataDir = tempdir("stt-data").toPath()
                    Database.open(dataDir).use { db -> QueueStateRepository(db).setPaused(true) }
                    val osTemp = Path.of(System.getProperty("java.io.tmpdir"))
                    val successSize = 8L * MIB + 4_321
                    var createdId = 0L
                    lateinit var disk: DiskWatch

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        startApplication()
                        disk = DiskWatch(listOf(dataDir, osTemp), allowed = databaseFiles(dataDir))

                        suspend fun upload(
                            filename: String,
                            body: InputStream,
                        ): HttpResponse =
                            client.submitFormWithBinaryData(
                                url = "/api/tasks",
                                formData =
                                    formData {
                                        append(
                                            "file",
                                            ChannelProvider { disk.watching(body).toByteReadChannel() },
                                            Headers.build {
                                                append(HttpHeaders.ContentType, "application/octet-stream")
                                                append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                                            },
                                        )
                                    },
                            )

                        val created = upload("long.wav", GeneratedBytes(successSize))
                        created.status shouldBe HttpStatusCode.Created
                        createdId =
                            Json
                                .parseToJsonElement(created.bodyAsText())
                                .jsonObject
                                .getValue("id")
                                .jsonPrimitive.content
                                .toLong()

                        runCatching { upload("broken.wav", FailingAfter(6L * MIB)) }

                        upload("notes.txt", GeneratedBytes(6L * MIB)).status shouldBe HttpStatusCode.BadRequest
                    }

                    disk.check()
                    disk.seen shouldBe emptySet()
                    Database.open(dataDir).use { db ->
                        rowCount(db, "tasks") shouldBe 1
                        val stored = ByteArrayOutputStream()
                        AudioRepository(db).copyOriginalTo(createdId, stored)
                        sha256Hex(stored.toByteArray().inputStream()) shouldBe sha256Hex(GeneratedBytes(successSize))
                        stored.size().toLong() shouldBe successSize
                    }
                }
            }
        }
    })

/** The database's own files in [dataDir], the only files allowed to appear there. */
private fun databaseFiles(dataDir: Path): Set<Path> = setOf("stt.db", "stt.db-wal", "stt.db-shm").map(dataDir::resolve).toSet()

/**
 * Records every regular file that appears under [roots] after construction and is not in [allowed]. [watching]
 * checks every [CHECK_EVERY] bytes the server reads from a body, so a spool that is deleted afterwards is still seen.
 */
private class DiskWatch(
    private val roots: List<Path>,
    private val allowed: Set<Path>,
) {
    private val before = roots.flatMap(::regularFilesUnder).toSet()
    val seen = mutableSetOf<Path>()

    fun check() {
        seen += roots.flatMap(::regularFilesUnder).filter { it !in before && it !in allowed }
    }

    fun watching(source: InputStream): InputStream =
        object : InputStream() {
            private var delivered = 0L
            private var nextCheck = CHECK_EVERY

            override fun read(): Int = source.read().also { if (it >= 0) advance(1) }

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int = source.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

            private fun advance(count: Long) {
                delivered += count
                if (delivered >= nextCheck) {
                    check()
                    nextCheck += CHECK_EVERY
                }
            }
        }

    private companion object {
        const val CHECK_EVERY = 256L * 1024
    }
}

private fun regularFilesUnder(root: Path): List<Path> {
    val files = mutableListOf<Path>()
    if (!Files.isDirectory(root)) return files
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                if (attrs.isRegularFile) files.add(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(
                file: Path,
                exc: IOException,
            ): FileVisitResult = FileVisitResult.CONTINUE
        },
    )
    return files
}
