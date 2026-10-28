package database.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import database.data.local.entity.DeclinedGroupEntity
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupMemberEntity
import database.data.local.entity.GroupWithMembersEntity
import database.data.local.entity.PendingGroupCreationEntity
import database.data.local.entity.PendingMembershipChangeEntity
import kotlinx.coroutines.flow.Flow

/** Whether a change to the group `:groupUuid` is waiting on the server: its creation, or a change to its members. */
private const val HAS_PENDING_CHANGE = """SELECT
    EXISTS (SELECT 1 FROM PendingGroupCreationEntity WHERE groupUuid = :groupUuid)
    OR EXISTS (SELECT 1 FROM PendingMembershipChangeEntity WHERE groupUuid = :groupUuid)"""

@Dao
interface GroupDao {
    @Insert
    suspend fun insert(groupEntity: GroupEntity)

    @Insert
    suspend fun insertMembers(groupMemberEntities: List<GroupMemberEntity>)

    @Insert
    suspend fun insertPendingCreation(pendingCreation: PendingGroupCreationEntity)

    /**
     * Records a group the user just created, with its [members] and the [pendingCreation] that
     * sends it, in one transaction: a group saved without its request would never reach the
     * server, and a request without its group would create one this device knows nothing of.
     */
    @Transaction
    suspend fun insertCreated(
        group: GroupEntity,
        members: List<GroupMemberEntity>,
        pendingCreation: PendingGroupCreationEntity,
    ) {
        insert(groupEntity = group)
        insertMembers(groupMemberEntities = members)
        insertPendingCreation(pendingCreation = pendingCreation)
    }

    /**
     * Takes the title and description the user gave the group [uuid], on this device only, or
     * nothing once the group is gone: a creation the server refused deletes its group under
     * whatever screen is open, see [deleteRefusedCreation].
     */
    @Query("UPDATE GroupEntity SET title = :title, description = :description WHERE uuid = :uuid")
    suspend fun updateTitleAndDescription(uuid: String, title: String, description: String)

    @Query("SELECT count(*) FROM GroupEntity")
    suspend fun count(): Int

    @Query("SELECT * FROM GroupEntity")
    fun getAllAsFlow(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM GroupEntity WHERE uuid = :uuid")
    fun getAsFlow(uuid: String): Flow<GroupEntity?>

    @Query("SELECT * FROM GroupEntity WHERE uuid = :uuid")
    fun getWithMembers(uuid: String): Flow<GroupWithMembersEntity?>

    /**
     * Emits whether the creator of the group [groupUuid] is blocked, see
     * [IdentityConflictEntity][database.data.local.entity.IdentityConflictEntity], as it changes,
     * false while there is no such group.
     */
    @Query(
        """SELECT EXISTS(SELECT 1 FROM GroupEntity
        WHERE uuid = :groupUuid AND ownerUuid IN (SELECT peerUuid FROM IdentityConflictEntity))""",
    )
    fun isCreatorBlockedAsFlow(groupUuid: String): Flow<Boolean>

    /** Every creation the server has not been heard to take yet, see [PendingGroupCreationEntity]. */
    @Query("SELECT * FROM PendingGroupCreationEntity")
    suspend fun getPendingCreations(): List<PendingGroupCreationEntity>

    /** Drops the creation of the group [groupUuid] once the server holds the group. */
    @Query("DELETE FROM PendingGroupCreationEntity WHERE groupUuid = :groupUuid")
    suspend fun deletePendingCreation(groupUuid: String)

    @Query("SELECT * FROM GroupEntity WHERE uuid = :uuid")
    suspend fun get(uuid: String): GroupEntity?

    @Query("DELETE FROM GroupMemberEntity WHERE groupUuid = :groupUuid")
    suspend fun deleteMembersOf(groupUuid: String)

    /**
     * Replaces the members of the group [groupUuid] with [members], in one transaction: those its
     * MLS group holds once a Commit changed them.
     */
    @Transaction
    suspend fun replaceMembers(groupUuid: String, members: List<GroupMemberEntity>) {
        deleteMembersOf(groupUuid = groupUuid)
        insertMembers(groupMemberEntities = members)
    }

    @Query("DELETE FROM GroupEntity WHERE uuid = :uuid")
    suspend fun deleteGroup(uuid: String)

    /**
     * Forgets the group [groupUuid] whose creation the server refused, with its members and its
     * pending creation, in one transaction, and returns the group as it was: what the caller is
     * left to delete, its MLS group and the vault's copy of its key, once the rows no longer name
     * them.
     */
    @Transaction
    suspend fun deleteRefusedCreation(groupUuid: String): GroupEntity? {
        val group = get(uuid = groupUuid)

        deletePendingCreation(groupUuid = groupUuid)
        deleteMembersOf(groupUuid = groupUuid)
        deleteGroup(uuid = groupUuid)

        return group
    }

    /**
     * The groups the user is still in whose key the vault does not hold yet, see
     * [GroupEntity.isBackedUp]: the key of one they are no longer in, removed or left, is of no
     * further use, the creator adding them back handing over the one the group has then.
     */
    @Query("SELECT * FROM GroupEntity WHERE isBackedUp = false AND isLeft = false")
    suspend fun getNotBackedUp(): List<GroupEntity>

    @Query("UPDATE GroupEntity SET isBackedUp = true WHERE uuid = :uuid")
    suspend fun markBackedUp(uuid: String)

    @Upsert
    suspend fun upsert(groupEntity: GroupEntity)

    /**
     * Records a group the user joined, joined again once added back, or brought back after a
     * log-out, with its [members] in place of any it had, in one transaction: a group listed
     * without its members would show nobody in it, and one joined again would show those it had
     * when the user was removed.
     */
    @Transaction
    suspend fun upsertWithMembers(group: GroupEntity, members: List<GroupMemberEntity>) {
        upsert(groupEntity = group)
        replaceMembers(groupUuid = group.uuid, members = members)
    }

    /** Every group this device holds, those the user is no longer in included, see [GroupEntity.isLeft]. */
    @Query("SELECT * FROM GroupEntity")
    suspend fun getAll(): List<GroupEntity>

    /** Who this device records as the members of the group [groupUuid]. */
    @Query("SELECT userUuid FROM GroupMemberEntity WHERE groupUuid = :groupUuid")
    suspend fun getMemberUuids(groupUuid: String): List<String>

    /**
     * Records that the user is no longer a member of the group [uuid], removed by its creator or
     * left, see [GroupEntity.isLeft].
     */
    @Query("UPDATE GroupEntity SET isLeft = true WHERE uuid = :uuid")
    suspend fun markLeft(uuid: String)

    /**
     * Records that the user left the group [groupUuid] and owes the server its leave, see
     * [DeclinedGroupEntity], in one transaction, see `group.GroupLeaver`: a group marked left with
     * no leave owed would keep the user a member on the server for good, nothing ever sending it,
     * and a leave owed for a group still current would have a sync drain it and report a removal
     * once the leave lands.
     */
    @Transaction
    suspend fun markLeftAndOweLeave(groupUuid: String) {
        markLeft(uuid = groupUuid)
        insertDeclined(declined = DeclinedGroupEntity(groupUuid = groupUuid))
    }

    /**
     * Records [handedProfileKeyHash] as the key to the user's profile the group [uuid] holds for
     * them, or none known to be current, see [GroupEntity.handedProfileKeyHash].
     */
    @Query("UPDATE GroupEntity SET handedProfileKeyHash = :handedProfileKeyHash WHERE uuid = :uuid")
    suspend fun setHandedProfileKeyHash(uuid: String, handedProfileKeyHash: String?)

    /**
     * The groups owed the key to the user's profile whose partial hash is [profileKeyHash], see
     * [GroupEntity.handedProfileKeyHash], that it can be posted into now: those the user is still
     * in, whose MLS group this device holds, whose creator is not blocked, see
     * [IdentityConflictEntity][database.data.local.entity.IdentityConflictEntity], and with no change
     * waiting on the server, its creation or a change to its members, which would leave the post in
     * an epoch the members are moving past, or in a group the server does not hold yet.
     */
    @Query(
        """SELECT * FROM GroupEntity
        WHERE isLeft = false
        AND mlsGroupId IS NOT NULL
        AND ownerUuid NOT IN (SELECT peerUuid FROM IdentityConflictEntity)
        AND uuid NOT IN (SELECT groupUuid FROM PendingGroupCreationEntity)
        AND uuid NOT IN (SELECT groupUuid FROM PendingMembershipChangeEntity)
        AND (handedProfileKeyHash IS NULL OR handedProfileKeyHash != :profileKeyHash)""",
    )
    suspend fun getOwedProfileKey(profileKeyHash: String): List<GroupEntity>

    /**
     * Records [change] as the one pending for its group, see [PendingMembershipChangeEntity],
     * replacing the removal a key rotation comes after.
     */
    @Upsert
    suspend fun upsertMembershipChange(change: PendingMembershipChangeEntity)

    /**
     * Every change to the members of a group the server has not been heard to take, or whose
     * taking this device has yet to act on, see [PendingMembershipChangeEntity].
     */
    @Query("SELECT * FROM PendingMembershipChangeEntity")
    suspend fun getMembershipChanges(): List<PendingMembershipChangeEntity>

    /** Drops the change pending for the group [groupUuid], once nothing is left to do about it. */
    @Query("DELETE FROM PendingMembershipChangeEntity WHERE groupUuid = :groupUuid")
    suspend fun deleteMembershipChange(groupUuid: String)

    /** Whether a change to the group [groupUuid] is waiting on the server: its creation, or a change to its members. */
    @Query(HAS_PENDING_CHANGE)
    suspend fun hasPendingChange(groupUuid: String): Boolean

    /** See [hasPendingChange]. */
    @Query(HAS_PENDING_CHANGE)
    fun hasPendingChangeAsFlow(groupUuid: String): Flow<Boolean>

    @Query("UPDATE GroupEntity SET groupKey = :groupKey, isBackedUp = false WHERE uuid = :uuid")
    suspend fun updateGroupKey(uuid: String, groupKey: ByteArray)

    /**
     * Takes [groupKey] as the key of the group [groupUuid], once the server took the key rotation
     * handing it over, and drops that rotation, in one transaction: a rotation dropped without its
     * key taken would lose the key the members now read the group with, while one whose key is
     * taken and that is sent again only hears it was taken already. The vault does not hold the
     * new key yet, see [GroupEntity.isBackedUp].
     */
    @Transaction
    suspend fun adoptRotatedKey(groupUuid: String, groupKey: ByteArray) {
        updateGroupKey(uuid = groupUuid, groupKey = groupKey)
        deleteMembershipChange(groupUuid = groupUuid)
    }

    /**
     * Whether one of the user's groups talks in the MLS group [mlsGroupId], which a direct message
     * must never be read in, see `message.DirectMessageSync`: one the user is no longer in too,
     * removed or left, which keeps its id, see [GroupEntity.mlsGroupId].
     */
    @Query("SELECT EXISTS (SELECT 1 FROM GroupEntity WHERE mlsGroupId = :mlsGroupId)")
    suspend fun hasMlsGroup(mlsGroupId: ByteArray): Boolean

    /**
     * Records the leave of the group [DeclinedGroupEntity.groupUuid] as owed, see
     * [DeclinedGroupEntity]: one this device declined, or that the user left, see
     * [markLeftAndOweLeave].
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDeclined(declined: DeclinedGroupEntity)

    /** The UUID of every group whose leave is owed, declined or left, see [DeclinedGroupEntity]. */
    @Query("SELECT groupUuid FROM DeclinedGroupEntity")
    suspend fun getDeclinedUuids(): List<String>

    /** Forgets the leave owed for the group [groupUuid], once the server took it, see [DeclinedGroupEntity]. */
    @Query("DELETE FROM DeclinedGroupEntity WHERE groupUuid = :groupUuid")
    suspend fun deleteDeclined(groupUuid: String)
}
