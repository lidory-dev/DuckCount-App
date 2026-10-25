package database.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
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
import database.domain.Currency
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

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

    /** Takes what the user edited of the group [uuid], leaving what opens and reaches it alone. */
    @Query("UPDATE GroupEntity SET title = :title, description = :description, currency = :currency WHERE uuid = :uuid")
    suspend fun updateDetails(uuid: String, title: String, description: String, currency: Currency)

    @Upsert
    suspend fun updateMembers(members: List<GroupMemberEntity>)

    /**
     * Takes what the user edited of the group [uuid] and its [members], or nothing once the group
     * is gone: a creation the server refused deletes its group under whatever screen is open, see
     * [deleteRefusedCreation], and members upserted for it would name a group that is not there.
     */
    @Transaction
    suspend fun updateWithMembers(
        uuid: String,
        title: String,
        description: String,
        currency: Currency,
        members: List<GroupMemberEntity>,
    ) {
        val oldUser = getWithMembers(uuid).first()?.groupMembers ?: return
        val userToRemove = oldUser - members.toSet()

        updateDetails(uuid = uuid, title = title, description = description, currency = currency)
        updateMembers(members)
        deleteMembers(groupMembers = userToRemove)
    }

    @Query("DELETE FROM GroupMemberEntity WHERE groupUuid = :groupUuid AND userUuid = :userUuid")
    suspend fun removeGroupMember(userUuid: String, groupUuid: String)

    @Delete
    suspend fun deleteMembers(groupMembers: List<GroupMemberEntity>)

    @Query("SELECT count(*) FROM GroupEntity")
    suspend fun count(): Int

    @Query("SELECT * FROM GroupEntity")
    fun getAllAsFlow(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM GroupEntity WHERE uuid = :uuid")
    fun getAsFlow(uuid: String): Flow<GroupEntity?>

    @Query("SELECT * FROM GroupEntity WHERE uuid = :uuid")
    fun getWithMembers(uuid: String): Flow<GroupWithMembersEntity?>

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

    /** The groups whose key the vault does not hold yet, see [GroupEntity.isBackedUp]. */
    @Query("SELECT * FROM GroupEntity WHERE isBackedUp = false")
    suspend fun getNotBackedUp(): List<GroupEntity>

    @Query("UPDATE GroupEntity SET isBackedUp = true WHERE uuid = :uuid")
    suspend fun markBackedUp(uuid: String)

    /**
     * Records a group the user joined, or one brought back after a log-out, with its [members], in
     * one transaction: a group listed without its members would show nobody in it.
     */
    @Transaction
    suspend fun insertWithMembers(group: GroupEntity, members: List<GroupMemberEntity>) {
        insert(groupEntity = group)
        insertMembers(groupMemberEntities = members)
    }

    /** The UUID of every group this device holds. */
    @Query("SELECT uuid FROM GroupEntity")
    suspend fun getUuids(): List<String>

    /**
     * Whether one of the user's groups talks in the MLS group [mlsGroupId], which a direct message
     * must never be read in, see `message.DirectMessageSync`.
     */
    @Query("SELECT EXISTS (SELECT 1 FROM GroupEntity WHERE mlsGroupId = :mlsGroupId)")
    suspend fun hasMlsGroup(mlsGroupId: ByteArray): Boolean

    /** Records the group [DeclinedGroupEntity.groupUuid] as declined, see [DeclinedGroupEntity]. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDeclined(declined: DeclinedGroupEntity)

    /** The UUID of every group this device declined, see [DeclinedGroupEntity]. */
    @Query("SELECT groupUuid FROM DeclinedGroupEntity")
    suspend fun getDeclinedUuids(): List<String>
}
