package networking

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

private const val RETRY_MILLIS = 1L

/**
 * How a pair a password authentication issued is stored, see [storeUntilTaken]: the token the
 * vault holds is one the server retired, so the pair goes on being stored until it is in.
 */
class StoreUntilTakenTest {

    /** On a locked device, until it is unlocked. */
    @Test
    fun `a pair the vault turns down is stored again until it takes it`() = runBlocking<Unit> {
        var tries = 0

        storeUntilTaken(
            store = { ++tries == 3 },
            hasSessionEnded = { false },
            retryMillis = RETRY_MILLIS,
        )

        assertEquals(3, tries)
    }

    @Test
    fun `a pair the vault takes is stored once`() = runBlocking<Unit> {
        var tries = 0

        storeUntilTaken(
            store = {
                tries++
                true
            },
            hasSessionEnded = { error("Not asked about a pair that went in") },
            retryMillis = RETRY_MILLIS,
        )

        assertEquals(1, tries)
    }

    /** Nothing is stored over an ended session, so trying again would never stop. */
    @Test
    fun `a pair is no longer stored once the session has ended`() = runBlocking<Unit> {
        var tries = 0

        storeUntilTaken(
            store = {
                tries++
                false
            },
            hasSessionEnded = { tries == 2 },
            retryMillis = RETRY_MILLIS,
        )

        assertEquals(2, tries)
    }
}
