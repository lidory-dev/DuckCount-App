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

/** What a group tells the screens of whether its members can be changed, and by whom. */
class GroupMappingTest {

    @Test
    fun `the creator holding the group may change its members`() {
        val group = groupOf(ownerUuid = ALICE).toGroupWithMembers(selfUuid = ALICE, hasPendingChange = false)

        assertTrue(group.canChangeMembers)
        assertFalse(group.isLeft)
        assertFalse(group.hasPendingChange)
        assertEquals(listOf(ALICE, BOB), group.groupMembers.map { it.userUuid })
    }

    /** It is still theirs to change once the change goes through. */
    @Test
    fun `a change pending is told apart from who may change the members`() {
        val group = groupOf(ownerUuid = ALICE).toGroupWithMembers(selfUuid = ALICE, hasPendingChange = true)

        assertTrue(group.hasPendingChange)
        assertTrue(group.canChangeMembers)
    }

    @Test
    fun `a member who did not create the group may not change its members`() {
        val group = groupOf(ownerUuid = ALICE).toGroupWithMembers(selfUuid = BOB, hasPendingChange = false)

        assertFalse(group.canChangeMembers)
    }

    /** A log-out takes every MLS group with it. */
    @Test
    fun `the creator of a group brought back after a log-out may not change its members here`() {
        val group = groupOf(ownerUuid = ALICE, mlsGroupId = null)
            .toGroupWithMembers(selfUuid = ALICE, hasPendingChange = false)

        assertFalse(group.canChangeMembers)
    }

    @Test
    fun `a group the user was removed from is left`() {
        val entity = groupOf(ownerUuid = ALICE, isLeft = true)

        assertTrue(entity.toGroupWithMembers(selfUuid = BOB, hasPendingChange = false).isLeft)
        assertTrue(entity.group.toGroup().isLeft)
    }

    @Test
    fun `nobody signed in created no group`() {
        assertFalse(
            groupOf(ownerUuid = ALICE).toGroupWithMembers(selfUuid = null, hasPendingChange = false).canChangeMembers,
        )
    }

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
