package stt.db

import io.kotest.matchers.booleans.shouldBeTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

// Deterministic race machinery (roadmap item 40): the atomic-transition specs interleave two commands by
// holding the writer lock, queuing the losing write first and the winner's write second, and releasing —
// the lock grants waiters in FIFO order, so the commit order is the launch order and the test is exact,
// not probabilistic. Shared by the engine and database specs (roadmap item 94).

/**
 * Names a coroutine that takes the writer lock: a race spec launches each racing write with its own
 * [LockWriter], so it can wait for exactly that write to queue. A context element, not the coroutine's
 * `Job`: `withContext` replaces the `Job` (the cancel's write runs under `NonCancellable`) but keeps the
 * element, and the engine worker's own writes never carry one.
 */
internal class LockWriter(
    private val name: String,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LockWriter>

    override fun toString() = name
}

/**
 * A [Mutex] that records which [LockWriter]s have called lock(), so a test can see a racing write reach it.
 * Each call takes a ticket in the same critical section as its record and is served in ticket order, so a
 * thread preempted right after its record cannot be overtaken by a later writer.
 */
internal class CountingMutex(
    private val delegate: Mutex = Mutex(),
) : Mutex by delegate {
    /** The writers that have called lock(); it only grows, so a wait for one writer cannot miss it. */
    val writers = MutableStateFlow<Set<LockWriter>>(emptySet())

    /**
     * Test-only: suspends a writer after it takes its ticket and before it waits for its turn, so a spec
     * can preempt it in that gap on purpose.
     */
    @Volatile
    var holdBeforeWait: CompletableDeferred<Unit>? = null

    private val state = Any()
    private val waiters = ArrayDeque<CompletableDeferred<Unit>>()
    private var held = false

    override suspend fun lock(owner: Any?) {
        val writer = currentCoroutineContext()[LockWriter]
        val turn = CompletableDeferred<Unit>()
        synchronized(state) {
            writer?.let { calling -> writers.update { current -> current + calling } }
            if (held) {
                waiters.addLast(turn)
            } else {
                held = true
                turn.complete(Unit)
            }
        }
        try {
            holdBeforeWait?.await()
            turn.await()
            delegate.lock(owner)
        } catch (e: CancellationException) {
            // A cancelled waiter must not stall the tickets behind it: drop its ticket, or pass on a turn it
            // was already granted (delegate.lock is cancellation-safe, so it never holds the lock here).
            synchronized(state) {
                if (turn.isCompleted) {
                    releaseTurn()
                } else {
                    waiters.remove(turn)
                }
            }
            throw e
        }
    }

    override fun unlock(owner: Any?) {
        delegate.unlock(owner)
        synchronized(state) {
            releaseTurn()
        }
    }

    /** Releases the held turn and serves the next ticket; the caller holds [state]. */
    private fun releaseTurn() {
        held = false
        serveNext()
    }

    /** Grants the turn to the next ticket; the caller holds [state]. */
    private fun serveNext() {
        val next = waiters.removeFirstOrNull() ?: return
        held = true
        next.complete(Unit)
    }
}

/** A write transaction held open on the writer connection; once released it fails, so it rolls back. */
internal class HeldWrite(
    private val releaseLatch: CountDownLatch,
    val outcome: Deferred<Result<Unit>>,
) {
    fun release() = releaseLatch.countDown()
}

/**
 * Starts a write transaction that inserts [task] and stays open until released; returns once it is open.
 * The held transaction owns the writer lock, so every write launched after it waits behind it (reads do
 * not: they use their own read-only connections).
 */
internal suspend fun CoroutineScope.holdWriteTransaction(
    db: Database,
    task: Task,
): HeldWrite {
    val held = CountDownLatch(1)
    val release = CountDownLatch(1)
    val outcome =
        async {
            runCatching {
                db.writeTransaction {
                    insertTaskRow(task)
                    held.countDown()
                    release.await(STALL_SAFETY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    throw IOException("held transaction failed")
                }
            }
        }
    withContext(Dispatchers.IO) { held.await(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS).shouldBeTrue() }
    return HeldWrite(release, outcome)
}

/**
 * Waits until [writer] has taken its ticket on the writer lock, so the write it launched is ahead of any
 * write launched after this wait. With [job], the wait also ends when that writer's job completes;
 * membership in the append-only [CountingMutex.writers] is the only success, so a writer that finished
 * without it fails naming [writer], at once and without a clock.
 */
internal suspend fun awaitLockCall(
    lock: CountingMutex,
    writer: LockWriter,
    job: Job,
) {
    // Kept with the cause the job completes with (null when it ended normally), so a failed or
    // cancelled writer's own failure stays attached to the assertion.
    val failure = CompletableDeferred<Throwable?>()
    job.invokeOnCompletion { failure.complete(it) }
    coroutineScope {
        val queued = async { lock.writers.first { writer in it } }
        val finishedFirst =
            select {
                queued.onAwait { false }
                job.onJoin { true }
            }
        if (finishedFirst) {
            queued.cancel()
            if (writer !in lock.writers.value) {
                throw AssertionError("writer $writer finished without calling lock()", failure.await())
            }
        }
    }
}

/**
 * Launches [write] on Dispatchers.Default as its own [LockWriter] named [name] and returns once that write
 * has called the writer lock, so it commits before any write launched after it (see [awaitLockCall]). A
 * write that completes without taking the lock fails naming [name].
 */
internal suspend fun <T> CoroutineScope.queueWrite(
    lock: CountingMutex,
    name: String,
    write: suspend CoroutineScope.() -> T,
): Deferred<T> {
    val writer = LockWriter(name)
    // The write's own failure must reach the probe, which reports it naming the writer, instead of
    // cancelling the caller's test scope; the supervisor keeps the caller alive to read it.
    val job = SupervisorJob(coroutineContext[Job])
    val deferred = async(Dispatchers.Default + writer + job, block = write)
    deferred.invokeOnCompletion { job.complete() }
    awaitLockCall(lock, writer, deferred)
    return deferred
}

// The held transaction's startup wait and stall safety bound are the race machinery's only real-time
// waits: the probe itself has no clock, and the stall bound outlasts every behavioral wait, so a stalled
// release cannot let a write pass behind it undetected.
private const val PROBE_TIMEOUT_MS = 10_000L
private const val STALL_SAFETY_TIMEOUT_MS = 30_000L
