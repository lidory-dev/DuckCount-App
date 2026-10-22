package database.data.repository

import database.data.local.entity.ContactEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "33333333-3333-3333-3333-333333333333"
private const val CAROL = "55555555-5555-5555-5555-555555555555"
private const val DAVE = "77777777-7777-7777-7777-777777777777"
private const val ERIN = "99999999-9999-9999-9999-999999999999"

/**
 * What a backup writes while the user's profile key has yet to rotate: Alice was just added,
 * the user removed Bob, Carol removed the user, Dave the user removed too, and Erin deleted her
 * account.
 */
class PendingBackUpTest {

    private val pending = PendingBackUp(
        contacts = listOf(
            contact(userUuid = ALICE),
            contact(userUuid = BOB, isRemoved = true, rotatesProfileKey = true),
            contact(userUuid = CAROL, isRemoved = true, rotatesProfileKey = false),
            contact(userUuid = DAVE, isRemoved = true, rotatesProfileKey = true),
            contact(userUuid = ERIN, isRemoved = true, isAccountDeleted = true),
        ),
    )

    /**
     * Written first, the removal would leave Bob holding the current key for good once a
     * log-out took the tombstone along, with nothing left saying the key has to change.
     */
    @Test
    fun `a removal the user made waits for the key to rotate`() {
        assertEquals(listOf(CAROL), pending.removals(isProfileKeyRotated = false).map { it.userUuid })
    }

    @Test
    fun `every removal goes out once the key has rotated`() {
        assertEquals(listOf(BOB, CAROL, DAVE), pending.removals(isProfileKeyRotated = true).map { it.userUuid })
    }

    /**
     * The key rotates on the user's own action only. Both go with one rotation, rather than
     * one each.
     */
    @Test
    fun `only the removals the user made call for a rotation`() {
        assertEquals(listOf(BOB, DAVE), pending.rotating.map { it.userUuid })
    }

    @Test
    fun `additions go out whatever comes of the rotation`() {
        assertEquals(listOf(ALICE), pending.added.map { it.userUuid })
    }

    /**
     * Her entry is turned into one holding her name, when this device holds one, rather than
     * written as a removal, and owes no rotation: the key only ever rotates on the user's own
     * action.
     */
    @Test
    fun `a contact who deleted their account goes out whatever comes of the rotation and not as a removal`() {
        assertEquals(listOf(ERIN), pending.deletedAccounts.map { it.userUuid })
        assertFalse(ERIN in pending.removals(isProfileKeyRotated = true).map { it.userUuid })
        assertFalse(ERIN in pending.rotating.map { it.userUuid })
    }

    /**
     * Erin's account deletion is all there is to write. Left for some other change to carry, it
     * would be lost to a log-out in between, and her name with it.
     */
    @Test
    fun `a contact who deleted their account is written on their own`() {
        val alone = PendingBackUp(contacts = listOf(contact(userUuid = ERIN, isRemoved = true, isAccountDeleted = true)))

        assertTrue(alone.writesAnything(isProfileKeyRotated = false))
    }

    @Test
    fun `a removal waiting for the rotation is nothing to write until the key has rotated`() {
        val waiting = PendingBackUp(contacts = listOf(contact(userUuid = BOB, isRemoved = true, rotatesProfileKey = true)))

        assertFalse(waiting.writesAnything(isProfileKeyRotated = false))
        assertTrue(waiting.writesAnything(isProfileKeyRotated = true))
    }

    /** Kept, Erin's tombstone would have every backup after write her again. */
    @Test
    fun `a write that landed is done with the removals it carried and the deleted accounts`() {
        assertEquals(listOf(CAROL, ERIN), pending.settled(isProfileKeyRotated = false).map { it.userUuid })
        assertEquals(listOf(BOB, CAROL, DAVE, ERIN), pending.settled(isProfileKeyRotated = true).map { it.userUuid })
    }

    @Test
    fun `a backup with nothing pending is empty`() {
        assertTrue(PendingBackUp(contacts = emptyList()).isEmpty())
        assertFalse(pending.isEmpty())
    }

    private fun contact(
        userUuid: String,
        isRemoved: Boolean = false,
        rotatesProfileKey: Boolean = false,
        isAccountDeleted: Boolean = false,
    ) =
        ContactEntity(
            userUuid = userUuid,
            profileEncryptionKey = byteArrayOf(1, 2, 3),
            handedProfileKeyHash = null,
            verifiedSafetyNumber = null,
            isBackedUp = false,
            isRemoved = isRemoved,
            rotatesProfileKey = rotatesProfileKey,
            isAccountDeleted = isAccountDeleted,
        )
}
