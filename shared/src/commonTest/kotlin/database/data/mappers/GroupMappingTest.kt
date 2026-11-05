package database.data.mappers

import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupMemberEntity
import database.data.local.entity.GroupWithMembersEntity
import database.domain.CurrencyCatalog
import group.FIRST_GROUP_REVISION
import group.FIRST_KEY_GENERATION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"
private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

/**
 * What a group tells the screens of whether its members can be changed, and by whom, of whether
 * its information can be edited, of whether it vouches for who they are, and of whether the user
 * may leave it.
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
    fun `a member who did not create the group may leave it`() {
        assertTrue(groupOf(ownerUuid = ALICE).seenBy(selfUuid = BOB).canLeave)
    }

    /** Nobody else could change its members. */
    @Test
    fun `the creator may not leave their own group`() {
        assertFalse(groupOf(ownerUuid = ALICE).seenBy(selfUuid = ALICE).canLeave)
    }

    @Test
    fun `a group the user is no longer in cannot be left again`() {
        assertFalse(groupOf(ownerUuid = ALICE, isLeft = true).seenBy(selfUuid = BOB).canLeave)
    }

    /** The server still has the user a member, and their leave is what takes them out. */
    @Test
    fun `a member's group brought back after a log-out may be left`() {
        assertTrue(groupOf(ownerUuid = ALICE, mlsGroupId = null).seenBy(selfUuid = BOB).canLeave)
    }

    @Test
    fun `the creator may edit the group`() {
        assertTrue(groupOf(ownerUuid = ALICE).seenBy(selfUuid = ALICE).canEdit)
    }

    /** Its members show no version of it but those its creator signed. */
    @Test
    fun `a member who did not create the group may not edit it`() {
        assertFalse(groupOf(ownerUuid = ALICE).seenBy(selfUuid = BOB).canEdit)
    }

    /** A version is signed with the user's identity, which a log-out does not take, unlike the group's MLS state. */
    @Test
    fun `the creator of a group brought back after a log-out may edit it`() {
        assertTrue(groupOf(ownerUuid = ALICE, mlsGroupId = null).seenBy(selfUuid = ALICE).canEdit)
    }

    /** It is still theirs to edit once the change goes through, which the edit waits for. */
    @Test
    fun `the creator may edit the group whatever is pending`() {
        assertTrue(groupOf(ownerUuid = ALICE).seenBy(selfUuid = ALICE, hasPendingChange = true).canEdit)
    }

    @Test
    fun `the creator may delete the group and not leave it`() {
        val group = groupOf(ownerUuid = ALICE).seenBy(selfUuid = ALICE)

        assertTrue(group.canDelete)
        assertFalse(group.canLeave)
    }

    @Test
    fun `a member may leave the group and not delete it`() {
        val group = groupOf(ownerUuid = ALICE).seenBy(selfUuid = BOB)

        assertFalse(group.canDelete)
        assertTrue(group.canLeave)
    }

    /** A deletion has no use for its MLS state, which a log-out takes. */
    @Test
    fun `the creator of a group brought back after a log-out may delete it`() {
        assertTrue(groupOf(ownerUuid = ALICE, mlsGroupId = null).seenBy(selfUuid = ALICE).canDelete)
    }

    /** Whatever else, they are told what became of it. */
    @Test
    fun `a group found deleted offers its creator nothing more and is told as deleted`() {
        val group = groupOf(ownerUuid = ALICE, isLeft = true, isDeleted = true).seenBy(selfUuid = ALICE)

        assertTrue(group.isDeleted)
        assertFalse(group.canDelete)
        assertFalse(group.canLeave)
        assertFalse(group.canEdit)
        assertFalse(group.canChangeMembers)
    }

    @Test
    fun `nobody signed in may delete no group`() {
        assertFalse(groupOf(ownerUuid = ALICE).seenBy(selfUuid = null).canDelete)
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
        isDeleted: Boolean = false,
    ) = GroupWithMembersEntity(
        group = GroupEntity(
            uuid = GROUP,
            title = "Ski trip",
            description = "Chalet and lift passes",
            currency = CurrencyCatalog.defaultCurrency,
            infoRevision = FIRST_GROUP_REVISION,
            ownerUuid = ownerUuid,
            mlsGroupId = mlsGroupId,
            groupKey = ByteArray(32),
            keyGeneration = FIRST_KEY_GENERATION,
            isBackedUp = true,
            isLeft = isLeft,
            handedProfileKeyHash = null,
            isDeleted = isDeleted,
        ),
        groupMembers = listOf(
            GroupMemberEntity(userUuid = ALICE, groupUuid = GROUP, isAdmin = ownerUuid == ALICE),
            GroupMemberEntity(userUuid = BOB, groupUuid = GROUP, isAdmin = ownerUuid == BOB),
        ),
    )
}
