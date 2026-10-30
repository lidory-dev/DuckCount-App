package database.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import database.data.local.entity.DeclinedGroupEntity
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupKeyEntity
import database.data.local.entity.GroupMemberEntity
import database.data.local.entity.GroupMemberSignatureKeyEntity
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
     * Records a group the user just created, with its [members], its key as the first of its
     * generations, see [GroupKeyEntity], and the [pendingCreation] that sends it, in one
     * transaction: a group saved without its request would never reach the server, and a request
     * without its group would create one this device knows nothing of.
     */
    @Transaction
    suspend fun insertCreated(
        group: GroupEntity,
        members: List<GroupMemberEntity>,
        pendingCreation: PendingGroupCreationEntity,
    ) {
        insert(groupEntity = group)
        insertMembers(groupMemberEntities = members)
        upsertKeys(keys = listOf(group.currentKey()))
        insertPendingCreation(pendingCreation = pendingCreation)
    }

    /**
     * Shows [title] and [description] as the version [infoRevision] of the information of the
     * group [uuid], see [GroupEntity.infoRevision]: one its creator signed, edited by the user, see
     * `group.GroupEditor`, or listed by the server, see `group.GroupSync`. Its currency stays the
     * one it was created in.
     */
    @Query(
        """UPDATE GroupEntity SET title = :title, description = :description, infoRevision = :infoRevision
        WHERE uuid = :uuid""",
    )
    suspend fun updateInformation(uuid: String, title: String, description: String, infoRevision: Long)

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

    /**
     * Whether the creation of the group [groupUuid] is waiting on the server, see
     * [PendingGroupCreationEntity]: nothing is written in it until it is taken, see
     * `group.GroupExpenseWriter`, so that a creation refused leaves nothing behind it but what
     * [deleteRefusedCreation] deletes.
     */
    @Query("SELECT EXISTS (SELECT 1 FROM PendingGroupCreationEntity WHERE groupUuid = :groupUuid)")
    suspend fun hasPendingCreation(groupUuid: String): Boolean

    /** Drops the creation of the group [groupUuid], see [deleteTakenCreation] and [deleteRefusedCreation]. */
    @Query("DELETE FROM PendingGroupCreationEntity WHERE groupUuid = :groupUuid")
    suspend fun deletePendingCreation(groupUuid: String)

    /**
     * Drops the creation of the group [groupUuid] once the server holds the group, and records the
     * group as backed up, see [markBackedUp], in one transaction: the server took the creator's copy
     * of its key with the creation, see `group.CreateGroupBody.keyCopy`. Its key is still the one it
     * was created with, nothing changing a group whose creation is pending, see [hasPendingChange].
     */
    @Transaction
    suspend fun deleteTakenCreation(groupUuid: String) {
        deletePendingCreation(groupUuid = groupUuid)
        markBackedUp(uuid = groupUuid)
    }

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

    /** Drops every generation of the key of the group [groupUuid] this device holds, see [GroupKeyEntity]. */
    @Query("DELETE FROM GroupKeyEntity WHERE groupUuid = :groupUuid")
    suspend fun deleteKeysOf(groupUuid: String)

    /**
     * Drops the signature key recorded for each member of the group [groupUuid], see
     * [GroupMemberSignatureKeyEntity].
     */
    @Query("DELETE FROM GroupMemberSignatureKeyEntity WHERE groupUuid = :groupUuid")
    suspend fun deleteSignatureKeysOf(groupUuid: String)

    /**
     * Forgets the group [groupUuid] whose creation the server refused, with its members, its key,
     * the signature keys recorded for its members and its pending creation, in one transaction,
     * and returns the group as it was: what the caller is left to delete, its MLS group, once the
     * rows no longer name it. The server stored no copy of its key, refusing the creation whole.
     */
    @Transaction
    suspend fun deleteRefusedCreation(groupUuid: String): GroupEntity? {
        val group = get(uuid = groupUuid)

        deletePendingCreation(groupUuid = groupUuid)
        deleteMembersOf(groupUuid = groupUuid)
        deleteKeysOf(groupUuid = groupUuid)
        deleteSignatureKeysOf(groupUuid = groupUuid)
        deleteGroup(uuid = groupUuid)

        return group
    }

    /**
     * Records that the server holds the user's copy of the key of the group [uuid], see
     * [GroupEntity.isBackedUp]. Under `group.withGroupsLock`, as every write of a group's key is,
     * so no other key can have replaced it while its copy was on its way.
     */
    @Query("UPDATE GroupEntity SET isBackedUp = true WHERE uuid = :uuid")
    suspend fun markBackedUp(uuid: String)

    /** Records that this device has taken in the expenses of the group [uuid], see [GroupEntity.hasTakenInExpenses]. */
    @Query("UPDATE GroupEntity SET hasTakenInExpenses = true WHERE uuid = :uuid")
    suspend fun markExpensesTakenIn(uuid: String)

    @Upsert
    suspend fun upsert(groupEntity: GroupEntity)

    /**
     * Records a group the user joined, joined again once added back, or brought back after a
     * log-out, with its [members] in place of any it had, its key as the generation it is and the
     * [olderKeys] read with it, see [GroupKeyEntity], and the [signatureKeys] of the members its
     * tree held, see [GroupMemberSignatureKeyEntity], in one transaction: a group listed without
     * its members would show nobody in it, one joined again would show those it had when the user
     * was removed, and one saved without its keys could not read what was sealed under them. The
     * generations and signature keys it held already stay, but for those read again.
     */
    @Transaction
    suspend fun upsertWithMembers(
        group: GroupEntity,
        members: List<GroupMemberEntity>,
        olderKeys: List<GroupKeyEntity>,
        signatureKeys: List<GroupMemberSignatureKeyEntity>,
    ) {
        upsert(groupEntity = group)
        replaceMembers(groupUuid = group.uuid, members = members)
        upsertKeys(keys = olderKeys + group.currentKey())
        upsertSignatureKeys(signatureKeys = signatureKeys)
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

    /**
     * Takes [groupKey] as the key of the group [uuid], of the generation [keyGeneration], recorded
     * as not backed up until the user's copy of it goes up, see [GroupEntity.isBackedUp]. The key
     * it replaces stays among the group's generations, see [GroupKeyEntity].
     */
    @Query(
        """UPDATE GroupEntity SET groupKey = :groupKey, keyGeneration = :keyGeneration, isBackedUp = false
        WHERE uuid = :uuid""",
    )
    suspend fun updateGroupKey(uuid: String, groupKey: ByteArray, keyGeneration: Long)

    /** Records [keys] among the generations of their groups' keys, in place of any held at the same generation. */
    @Upsert
    suspend fun upsertKeys(keys: List<GroupKeyEntity>)

    /**
     * Records those of [keys] this device holds no key of the same generation for, see
     * [insertRecoveredKeys] and [takeHandedKeys].
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissingKeys(keys: List<GroupKeyEntity>)

    /**
     * Takes [keys], at least one, which the creator of the group [groupUuid] handed over in it,
     * among the generations of its key, see [GroupKeyEntity], none in place of a key this device
     * holds of the same generation, and the one of the highest generation as its key, see
     * [updateGroupKey], when it is past the group's, in one transaction: the group would otherwise
     * seal under a key whose generation this device does not hold, or hold one it never seals
     * under.
     *
     * Never one behind the group's key, whatever order they come in: the server can hold a key
     * message back and list it after the next rotation's, which MLS still opens for a few epochs,
     * and taken as the group's key it would have every expense the user writes sealed for good under
     * a key a member removed since holds, see `group.GroupExpenseWriter`.
     */
    @Transaction
    suspend fun takeHandedKeys(groupUuid: String, keys: List<GroupKeyEntity>) {
        val newest = keys.maxBy { it.generation }

        insertMissingKeys(keys = keys)

        val held = get(uuid = groupUuid) ?: return

        if (newest.generation > held.keyGeneration) {
            updateGroupKey(uuid = groupUuid, groupKey = newest.groupKey, keyGeneration = newest.generation)
        }
    }

    /**
     * Takes [groupKey] as the key of the group [groupUuid], of the generation [keyGeneration], once
     * the server took the key rotation handing it over, records the group as backed up, the rotation
     * having carried the creator's copy of it, see `group.RotateGroupKeyBody.keyCopy`, and drops that
     * rotation, in one transaction: a rotation dropped without its key taken would lose the key the
     * members now read the group with, while one whose key is taken and that is sent again only hears
     * it was taken already. The key it replaces stays among the group's generations, see
     * [GroupKeyEntity].
     */
    @Transaction
    suspend fun adoptRotatedKey(groupUuid: String, groupKey: ByteArray, keyGeneration: Long) {
        val key = GroupKeyEntity(groupUuid = groupUuid, generation = keyGeneration, groupKey = groupKey)

        upsertKeys(keys = listOf(key))
        updateGroupKey(uuid = groupUuid, groupKey = groupKey, keyGeneration = keyGeneration)
        markBackedUp(uuid = groupUuid)
        deleteMembershipChange(groupUuid = groupUuid)
    }

    /** Every generation of the key of the group [groupUuid] this device holds, see [GroupKeyEntity]. */
    @Query("SELECT * FROM GroupKeyEntity WHERE groupUuid = :groupUuid")
    suspend fun getKeys(groupUuid: String): List<GroupKeyEntity>

    /**
     * Records [keys], generations of the key of the group [groupUuid] got back through the links
     * the server keeps, see `group.GroupKeyChain`, in one transaction: none in place of a key this
     * device holds of the same generation, which its creator handed over or the user's copy held,
     * and none for a group this device no longer holds, its creation refused while the links were
     * read, which would outlive it until a log-out.
     */
    @Transaction
    suspend fun insertRecoveredKeys(groupUuid: String, keys: List<GroupKeyEntity>) {
        if (get(uuid = groupUuid) == null) return

        insertMissingKeys(keys = keys)
    }

    /**
     * Records [signatureKeys], those the members of a group sign with as its tree holds them, see
     * [GroupMemberSignatureKeyEntity], in place of any recorded for the same members.
     */
    @Upsert
    suspend fun upsertSignatureKeys(signatureKeys: List<GroupMemberSignatureKeyEntity>)

    /**
     * The signature key [userUuid] signs with as the tree of the group [groupUuid] last held them,
     * see [GroupMemberSignatureKeyEntity], once they left it too; or, when this device never read
     * them there, as the tree of another group of the same creator's held them, their MLS identity
     * being the same in every group; or null when it read them in none: a member who left before
     * the user joined, and everybody in a group brought back after a log-out, which holds no tree.
     *
     * Never another creator's: a tree is only its creator's word for who everybody else in it is,
     * which this device takes for the group they created and nowhere else. Any contact of the
     * user's could otherwise create a group seating a leaf of their own under a member's UUID, and
     * have this device refuse what that member truly wrote in a group the contact is not in.
     */
    @Query(
        """SELECT signatureKey FROM GroupMemberSignatureKeyEntity
        WHERE userUuid = :userUuid
        AND groupUuid IN (
            SELECT uuid FROM GroupEntity
            WHERE ownerUuid = (SELECT ownerUuid FROM GroupEntity WHERE uuid = :groupUuid)
        )
        ORDER BY groupUuid = :groupUuid DESC
        LIMIT 1""",
    )
    suspend fun getMemberSignatureKey(groupUuid: String, userUuid: String): ByteArray?

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

/** The key this group holds, as the generation of its keys it is, see [GroupEntity.keyGeneration]. */
private fun GroupEntity.currentKey() = GroupKeyEntity(groupUuid = uuid, generation = keyGeneration, groupKey = groupKey)
