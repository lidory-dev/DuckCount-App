package networking

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import util.NetworkError
import util.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

private const val TIMEOUT_MILLIS = 200L

class WithinTimeoutTest {

    /**
     * The breach check stuck in a host name lookup, which does not stop on being cancelled: the
     * caller still gets its answer once the time is up rather than when the lookup gives up.
     */
    @Test
    fun `a request deaf to cancelling is given up on once the time is up`() = runBlocking<Unit> {
        val start = TimeSource.Monotonic.markNow()

        val result = withinTimeout<String>(timeoutMillis = TIMEOUT_MILLIS) {
            withContext(NonCancellable) { delay(TIMEOUT_MILLIS * 10) }
            Result.Success("too late")
        }

        assertEquals(Result.Error(NetworkError.REQUEST_TIMEOUT), result)
        assertTrue(start.elapsedNow() < (TIMEOUT_MILLIS * 5).milliseconds, "waited ${start.elapsedNow()}")
    }

    @Test
    fun `a request answering in time is passed on`() = runBlocking<Unit> {
        val result = withinTimeout(timeoutMillis = TIMEOUT_MILLIS) { Result.Success("range") }

        assertEquals(Result.Success("range"), result)
    }

    @Test
    fun `a request failing in time is passed on`() = runBlocking<Unit> {
        val result = withinTimeout<String>(timeoutMillis = TIMEOUT_MILLIS) { Result.Error(NetworkError.UNREACHABLE) }

        assertEquals(Result.Error(NetworkError.UNREACHABLE), result)
    }

    @Test
    fun `a request given up on is cancelled`() = runBlocking<Unit> {
        val cancelled = CompletableDeferred<Unit>()

        withinTimeout<String>(timeoutMillis = TIMEOUT_MILLIS) {
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }

        withTimeout(TIMEOUT_MILLIS * 5) { cancelled.await() }
    }
}
