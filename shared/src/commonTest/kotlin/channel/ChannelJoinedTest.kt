package channel

import contact.ContactCard
import contact.ContactRemoval
import expense.ExpenseKeyRequest
import expense.ExpenseKeyShare
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ChannelJoinedTest {

    /** It is read off the same channel as every other payload, and must be taken for none. */
    @Test
    fun `a channel_joined is taken for no other payload`() {
        val payload = ChannelJoined.serialize()

        assertNull(ContactCard.parse(plaintext = payload))
        assertFalse(ContactRemoval.matches(plaintext = payload))
        assertNull(ExpenseKeyShare.parse(plaintext = payload))
        assertNull(ExpenseKeyRequest.parse(plaintext = payload))
    }
}
