package stt.http

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineExceptionHandler
import stt.db.Database
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs an application in the test engine and afterwards asserts the shutdown invariants this item
 * guards (ADR 0038): the application stopped without any uncaught coroutine exception (the shape of
 * the flaky Nix failure — the Ktor test engine rethrows escaped exceptions into the test), and the
 * DI-provided [Database] was closed during the stop.
 *
 * [block] is the plain `testApplication { … }` body, so `client`, `application` and `startApplication`
 * resolve exactly as they do there. The body's own assertions run before the stop.
 */
internal fun verifyCleanStop(block: suspend ApplicationTestBuilder.() -> Unit) {
    val escaped = AtomicBoolean(false)
    var db: Database? = null

    testApplication(CoroutineExceptionHandler { _, _ -> escaped.set(true) }) {
        block()
        db = application.dependencies
            .resolve<Database>()
    }

    escaped.get().shouldBeFalse()
    db!!.connection.isClosed.shouldBeTrue()
}