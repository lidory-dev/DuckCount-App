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

/**
 * What a backup writes while the user's profile key has yet to rotate: Alice was just added,
 * the user removed Bob, Carol removed the user, and Dave the user removed too.
 */
class PendingBackUpTest {

    private val pending = PendingBackUp(
        contacts = listOf(
            contact(userUuid = ALICE),
            contact(userUuid = BOB, isRemoved = true, rotatesProfileKey = true),
            contact(userUuid = CAROL, isRemoved = true, rotatesProfileKey = false),
            contact(userUuid = DAVE, isRemoved = true, rotatesProfileKey = true),
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

    @Test
    fun `a backup with nothing pending is empty`() {
        assertTrue(PendingBackUp(contacts = emptyList()).isEmpty())
        assertFalse(pending.isEmpty())
    }

    private fun contact(userUuid: String, isRemoved: Boolean = false, rotatesProfileKey: Boolean = false) =
        ContactEntity(
            userUuid = userUuid,
            profileEncryptionKey = byteArrayOf(1, 2, 3),
            handedProfileKeyHash = null,
            verifiedSafetyNumber = null,
            isBackedUp = false,
            isRemoved = isRemoved,
            rotatesProfileKey = rotatesProfileKey,
        )
}
