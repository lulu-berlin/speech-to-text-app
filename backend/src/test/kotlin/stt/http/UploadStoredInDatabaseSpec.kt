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
import io.ktor.server.testing.ApplicationTestBuilder
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
import stt.sharedOsTempDirs
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
 * neither in the data directory nor in the app's temp dir (the whole `java.io.tmpdir`, which the Gradle test tasks
 * point at a private directory under `build/`), during the upload, after success, after a mid-body failure, or after
 * a 400 rejection. The stored original read back from the database equals the uploaded bytes. Files other processes
 * write to the shared OS temp dir are not the app's and do not count (item 77). Every body is several MiB, larger
 * than what the test client buffers ahead, so the disk checks taken while the client delivers a body run while the
 * server is consuming it.
 */
class UploadStoredInDatabaseSpec :
    BehaviorSpec({

        given("a data directory with the queue paused") {
            `when`("an 8 MiB upload succeeds, a body breaks after 6 MiB, and a 6 MiB .txt upload is rejected") {
                then("no file appears outside the database at any point, and the stored original equals the upload") {
                    val dataDir = tempdir("stt-data").toPath()
                    Database.open(dataDir).use { db -> QueueStateRepository(db).setPaused(true) }
                    val appTemp = Path.of(System.getProperty("java.io.tmpdir"))
                    val successSize = 8L * MIB + 4_321
                    var createdId = 0L
                    lateinit var disk: DiskWatch

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        startApplication()
                        disk = DiskWatch(listOf(dataDir, appTemp), allowed = databaseFiles(dataDir))

                        val created = upload(disk, "long.wav", GeneratedBytes(successSize))
                        created.status shouldBe HttpStatusCode.Created
                        createdId =
                            Json
                                .parseToJsonElement(created.bodyAsText())
                                .jsonObject
                                .getValue("id")
                                .jsonPrimitive.content
                                .toLong()

                        runCatching { upload(disk, "broken.wav", FailingAfter(6L * MIB)) }

                        upload(disk, "notes.txt", GeneratedBytes(6L * MIB)).status shouldBe HttpStatusCode.BadRequest
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

            `when`("another writer creates a file in the shared OS temp dir while an 8 MiB upload streams") {
                then("the watch over the data dir and the app's temp dir records no file") {
                    val dataDir = tempdir("stt-data").toPath()
                    Database.open(dataDir).use { db -> QueueStateRepository(db).setPaused(true) }
                    val appTemp = Path.of(System.getProperty("java.io.tmpdir"))
                    val sharedTemps = sharedOsTempDirs()
                    check(sharedTemps.isNotEmpty()) { "no shared OS temp dir found to write into" }
                    val planted = mutableListOf<Path>()
                    lateinit var disk: DiskWatch

                    try {
                        testApplication {
                            application { sttApp(stt.config.testConfig(dataDir)) }
                            startApplication()
                            disk = DiskWatch(listOf(dataDir, appTemp), allowed = databaseFiles(dataDir))
                            val body =
                                ActingAfter(1L * MIB, GeneratedBytes(8L * MIB + 4_321)) {
                                    planted += sharedTemps.map { Files.createTempFile(it, "other-writer-", ".tmp") }
                                }

                            upload(disk, "long.wav", body).status shouldBe HttpStatusCode.Created
                        }

                        disk.check()
                        planted.size shouldBe sharedTemps.size
                        disk.seen shouldBe emptySet()
                    } finally {
                        planted.forEach(Files::deleteIfExists)
                    }
                }
            }

            `when`("a file appears in the data dir and in the app's temp dir, and both are gone 64 KiB later") {
                then("the check fails and names both files") {
                    val dataDir = tempdir("stt-data").toPath()
                    Database.open(dataDir).use { db -> QueueStateRepository(db).setPaused(true) }
                    val appTemp = Path.of(System.getProperty("java.io.tmpdir"))
                    val leaked = mutableListOf<Path>()
                    lateinit var disk: DiskWatch

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        startApplication()
                        disk = DiskWatch(listOf(dataDir, appTemp), allowed = databaseFiles(dataDir))
                        // The stand-in for the app writes one file in each watched root between two periodic
                        // checks and looks right away; both are gone 64 KiB later, before the next check.
                        val written =
                            ActingAfter(1L * MIB + 128L * 1024, GeneratedBytes(8L * MIB + 4_321)) {
                                leaked.add(Files.createFile(dataDir.resolve("spool-leak.part")).toRealPath())
                                leaked.add(Files.createFile(appTemp.resolve("stt-spool-leak.part")).toRealPath())
                                disk.check()
                            }
                        val body = ActingAfter(1L * MIB + 192L * 1024, written) { leaked.forEach(Files::deleteIfExists) }

                        upload(disk, "long.wav", body).status shouldBe HttpStatusCode.Created
                    }

                    disk.check()
                    disk.seen shouldBe leaked.toSet()
                    leaked.forEach { Files.exists(it) shouldBe false }
                }
            }
        }

        given("a watch over a directory reached through a symlink") {
            `when`("the directory already holds a file when the watch starts") {
                // Windows needs SeCreateSymbolicLinkPrivilege (Developer Mode or elevation) to create a link, so
                // the scenario is skipped where the host refuses rather than failing the suite for a privilege it
                // cannot grant (root review 2026-10-23).
                then("the check reports no file: only files that appear after the start count").config(
                    enabled = symlinksUsable,
                ) {
                    val real = tempdir("stt-real").toPath()
                    Files.createFile(real.resolve("old.txt"))
                    val link = Files.createSymbolicLink(tempdir("stt-link").toPath().resolve("dir"), real)

                    val disk = DiskWatch(listOf(link), allowed = emptySet())
                    disk.check()

                    disk.seen shouldBe emptySet()
                }
            }
        }
    })

/** Whether this host can create a symbolic link: false on Windows without Developer Mode or elevation. */
private val symlinksUsable: Boolean by lazy {
    runCatching {
        val dir = Files.createTempDirectory("symlink-probe")
        Files.createSymbolicLink(dir.resolve("link"), dir)
    }.isSuccess
}

/**
 * The database's own files in [dataDir], the only files allowed to appear there, as real paths: the
 * watch compares them with the paths it scans through [dataDir]'s real path, and the `-wal`/`-shm`
 * sidecars may not exist yet, so this resolves the directory once instead of every file.
 */
private fun databaseFiles(dataDir: Path): Set<Path> {
    val dir = dataDir.toRealPath()
    return setOf("stt.db", "stt.db-wal", "stt.db-shm").mapTo(mutableSetOf(), dir::resolve)
}

/** Uploads [body] as [filename] to `POST /api/tasks`, with [disk] checking the disk while the server reads it. */
private suspend fun ApplicationTestBuilder.upload(
    disk: DiskWatch,
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

/** Delivers [source] and runs [action] once, when the first [atByte] bytes have been read. */
private class ActingAfter(
    private val atByte: Long,
    private val source: InputStream,
    private val action: () -> Unit,
) : InputStream() {
    private var delivered = 0L
    private var acted = false

    override fun read(): Int = source.read().also { if (it >= 0) advance(1) }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int = source.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

    private fun advance(count: Long) {
        delivered += count
        if (!acted && delivered >= atByte) {
            acted = true
            action()
        }
    }
}

/**
 * Records every regular file that appears under [roots] after construction and is not in [allowed]. [watching]
 * checks every [CHECK_EVERY] bytes the server reads from a body; [seen] keeps a recorded path after its file is
 * gone, so a spool that is deleted again before the upload ends is still named.
 */
private class DiskWatch(
    roots: List<Path>,
    /** Given as real paths by the caller, so a name that does not exist yet needs no resolution here. */
    private val allowed: Set<Path>,
) {
    private val roots = roots.map { it.toRealPath() }

    // `this.roots`: the resolved property above, not the constructor parameter of the same name, so the
    // baseline and check() scan the same paths (a root reached through a symlink would otherwise report
    // every pre-existing file as new).
    private var before = this.roots.flatMap(::regularFilesUnder).toSet()
    private val seenFiles = mutableSetOf<Path>()

    /** Every file that was not there before and is not in [allowed], sorted so a failure names each one. */
    val seen: Set<Path>
        get() = seenFiles.toSortedSet()

    fun check() {
        val now = roots.flatMap(::regularFilesUnder).toSet()
        seenFiles += (now - before).filter { it !in allowed }
        // A recorded file still counts as leaked once it is gone, so the baseline keeps it.
        before = now + seenFiles
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
