package push

import database.data.local.entity.OutgoingContactRequestEntity
import kotlin.test.Test
import kotlin.test.assertEquals

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val MALLORY = "77777777-7777-7777-7777-777777777777"

private const val DAY = 24L * 60 * 60 * 1000

/** Some moment, far enough from zero that nothing below can go negative. */
private const val T = 1_800_000_000_000L

/**
 * Whose answer may still wake the user, see [wakingRecipients]: Mallory refused the request
 * the user sent her, which the user's device never hears of.
 */
class RequestWakeWindowTest {

    @Test
    fun `a request just sent lets its recipient wake the user`() {
        assertEquals(setOf(ALICE), wakingRecipients(listOf(sent(to = ALICE, at = T)), now = T))
    }

    @Test
    fun `a request lets its recipient wake the user for a week`() {
        assertEquals(setOf(ALICE), wakingRecipients(listOf(sent(to = ALICE, at = T)), now = T + 7 * DAY - 1))
    }

    @Test
    fun `a request refused a week ago no longer lets its recipient wake the user`() {
        assertEquals(emptySet(), wakingRecipients(listOf(sent(to = MALLORY, at = T)), now = T + 7 * DAY))
        assertEquals(emptySet(), wakingRecipients(listOf(sent(to = MALLORY, at = T)), now = T + 300 * DAY))
    }

    /** The user asking her again: the server dropped the first one, this device still holds it. */
    @Test
    fun `a fresh request lets its recipient wake the user whatever came of an older one`() {
        val requests = listOf(sent(to = MALLORY, at = T), sent(to = MALLORY, at = T + 10 * DAY))

        assertEquals(setOf(MALLORY), wakingRecipients(requests, now = T + 11 * DAY))
    }

    @Test
    fun `a request sent before the clock was set back no longer lets its recipient wake the user`() {
        assertEquals(emptySet(), wakingRecipients(listOf(sent(to = ALICE, at = T)), now = T - 1))
    }

    private fun sent(to: String, at: Long) =
        OutgoingContactRequestEntity(mlsGroupId = ByteArray(16) { at.toByte() }, recipientUserUuid = to, sentAt = at)
}
