package database.data.mappers

import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupMemberEntity
import database.data.local.entity.GroupWithMembersEntity
import database.domain.CurrencyCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"
private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

/**
 * What a group tells the screens of whether its members can be changed, and by whom, and of
 * whether it vouches for who they are.
 */
class GroupMappingTest {

    @Test
    fun `the creator holding the group may change its members`() {
        val group = groupOf(ownerUuid = ALICE).seenBy(selfUuid = ALICE)

        assertTrue(group.canChangeMembers)
        assertFalse(group.isLeft)
        assertFalse(group.hasPendingChange)
        assertEquals(listOf(ALICE, BOB), group.groupMembers.map { it.userUuid })
    }

    /** It is still theirs to change once the change goes through. */
    @Test
    fun `a change pending is told apart from who may change the members`() {
        val group = groupOf(ownerUuid = ALICE).seenBy(selfUuid = ALICE, hasPendingChange = true)

        assertTrue(group.hasPendingChange)
        assertTrue(group.canChangeMembers)
    }

    @Test
    fun `a member who did not create the group may not change its members`() {
        val group = groupOf(ownerUuid = ALICE).seenBy(selfUuid = BOB)

        assertFalse(group.canChangeMembers)
    }

    /** A log-out takes every MLS group with it. */
    @Test
    fun `the creator of a group brought back after a log-out may not change its members here`() {
        val group = groupOf(ownerUuid = ALICE, mlsGroupId = null).seenBy(selfUuid = ALICE)

        assertFalse(group.canChangeMembers)
    }

    @Test
    fun `a group the user was removed from is left`() {
        val entity = groupOf(ownerUuid = ALICE, isLeft = true)

        assertTrue(entity.seenBy(selfUuid = BOB).isLeft)
        assertTrue(entity.group.toGroup().isLeft)
    }

    @Test
    fun `a group held here whose creator is not blocked vouches for its members`() {
        val group = groupOf(ownerUuid = ALICE).seenBy(selfUuid = BOB)

        assertTrue(group.canVouchForMembers)
    }

    /** Its MLS state was deleted with the removal. */
    @Test
    fun `a group the user was removed from vouches for nobody`() {
        val group = groupOf(ownerUuid = ALICE, isLeft = true).seenBy(selfUuid = BOB)

        assertFalse(group.canVouchForMembers)
    }

    /** A log-out takes every MLS group with it, and the group is brought back without one. */
    @Test
    fun `a group brought back after a log-out vouches for nobody`() {
        val group = groupOf(ownerUuid = ALICE, mlsGroupId = null).seenBy(selfUuid = BOB)

        assertFalse(group.canVouchForMembers)
    }

    /** Its tree is their word, which may be an impostor's. */
    @Test
    fun `a group whose creator is blocked vouches for nobody`() {
        val group = groupOf(ownerUuid = ALICE).seenBy(selfUuid = BOB, isCreatorBlocked = true)

        assertFalse(group.canVouchForMembers)
    }

    @Test
    fun `nobody signed in created no group`() {
        assertFalse(groupOf(ownerUuid = ALICE).seenBy(selfUuid = null).canChangeMembers)
    }

    private fun GroupWithMembersEntity.seenBy(
        selfUuid: String?,
        hasPendingChange: Boolean = false,
        isCreatorBlocked: Boolean = false,
    ) = toGroupWithMembers(selfUuid = selfUuid, hasPendingChange = hasPendingChange, isCreatorBlocked = isCreatorBlocked)

    private fun groupOf(
        ownerUuid: String,
        mlsGroupId: ByteArray? = ByteArray(32),
        isLeft: Boolean = false,
    ) = GroupWithMembersEntity(
        group = GroupEntity(
            uuid = GROUP,
            title = "Ski trip",
            description = "Chalet and lift passes",
            currency = CurrencyCatalog.defaultCurrency,
            ownerUuid = ownerUuid,
            mlsGroupId = mlsGroupId,
            groupKey = ByteArray(32),
            isBackedUp = true,
            isLeft = isLeft,
        ),
        groupMembers = listOf(
            GroupMemberEntity(userUuid = ALICE, groupUuid = GROUP, isAdmin = ownerUuid == ALICE),
            GroupMemberEntity(userUuid = BOB, groupUuid = GROUP, isAdmin = ownerUuid == BOB),
        ),
    )
}
