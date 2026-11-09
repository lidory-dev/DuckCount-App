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

private const val HAS_CHANGE_ON_SERVER = """SELECT
    EXISTS (SELECT 1 FROM PendingGroupCreationEntity WHERE groupUuid = :groupUuid)
    OR EXISTS (SELECT 1 FROM PendingMembershipChangeEntity WHERE groupUuid = :groupUuid AND isOutdated = false)"""

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
     * Forgets the group [groupUuid], which the user deleted and the server no longer holds, see
     * `group.GroupDeleter`, with everything this device kept of it, in one transaction, and leaves
     * the caller to delete its MLS group once the rows no longer name it. No change waits in it,
     * the deletion refusing one that does.
     */
    @Transaction
    suspend fun forgetDeleted(groupUuid: String) {
        deleteBeneficiariesOfGroup(groupUuid = groupUuid)
        deleteRevisionsOfGroup(groupUuid = groupUuid)
        deleteExpenseDeletionsOfGroup(groupUuid = groupUuid)
        deleteExpensesOfGroup(groupUuid = groupUuid)
        deleteListedExpensesOfGroup(groupUuid = groupUuid)
        deleteUnreadableExpensesOfGroup(groupUuid = groupUuid)
        deleteChangeFeed(feed = groupExpensesFeedOf(groupUuid))
        deleteMembersOf(groupUuid = groupUuid)
        deleteKeysOf(groupUuid = groupUuid)
        deleteSignatureKeysOf(groupUuid = groupUuid)
        deleteGroup(uuid = groupUuid)
    }

    @Query(
        """DELETE FROM BeneficiaryEntity
        WHERE expenseUuid IN (SELECT uuid FROM ExpenseEntity WHERE groupUuid = :groupUuid)""",
    )
    suspend fun deleteBeneficiariesOfGroup(groupUuid: String)

    @Query(
        """DELETE FROM ExpenseRevisionEntity
        WHERE expenseUuid IN (SELECT uuid FROM ExpenseEntity WHERE groupUuid = :groupUuid)""",
    )
    suspend fun deleteRevisionsOfGroup(groupUuid: String)

    @Query(
        """DELETE FROM GroupExpenseDeletionEntity
        WHERE expenseUuid IN (SELECT uuid FROM ExpenseEntity WHERE groupUuid = :groupUuid)""",
    )
    suspend fun deleteExpenseDeletionsOfGroup(groupUuid: String)

    @Query("DELETE FROM ExpenseEntity WHERE groupUuid = :groupUuid")
    suspend fun deleteExpensesOfGroup(groupUuid: String)

    @Query("DELETE FROM ListedGroupExpenseEntity WHERE groupUuid = :groupUuid")
    suspend fun deleteListedExpensesOfGroup(groupUuid: String)

    @Query("DELETE FROM UnreadableGroupExpenseEntity WHERE groupUuid = :groupUuid")
    suspend fun deleteUnreadableExpensesOfGroup(groupUuid: String)

    @Query("DELETE FROM ChangeFeedEntity WHERE feed = :feed")
    suspend fun deleteChangeFeed(feed: String)

    /**
     * Records that the user is no longer a member of the group [groupUuid] because the server no
     * longer holds it, see [GroupEntity.isDeleted], in one transaction with dropping any change to
     * its members waiting on the server, which nothing can take now, see
     * [PendingMembershipChangeEntity]. Left, it would be sent again by every sync, from a group
     * whose MLS state this deletes.
     */
    @Transaction
    suspend fun markDeleted(groupUuid: String) {
        markDeletedRow(uuid = groupUuid)
        deleteAllMembershipChanges(groupUuid = groupUuid)
    }

    @Query("UPDATE GroupEntity SET isLeft = true, isDeleted = true WHERE uuid = :uuid")
    suspend fun markDeletedRow(uuid: String)

    @Query("DELETE FROM PendingMembershipChangeEntity WHERE groupUuid = :groupUuid")
    suspend fun deleteAllMembershipChanges(groupUuid: String)

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
     * log-out, with its [members] in place of any it had, the [keys] read with it, its own among
     * them, see [GroupKeyEntity], and the [signatureKeys] of the members its tree held, see
     * [GroupMemberSignatureKeyEntity], in one transaction: a group listed without its members
     * would show nobody in it, one joined again would show those it had when the user was removed,
     * and one saved without its keys could not read what was sealed under them. The generations
     * and signature keys it held already stay, but for those read again.
     */
    @Transaction
    suspend fun upsertWithMembers(
        group: GroupEntity,
        members: List<GroupMemberEntity>,
        keys: List<GroupKeyEntity>,
        signatureKeys: List<GroupMemberSignatureKeyEntity>,
    ) {
        upsert(groupEntity = group)
        replaceMembers(groupUuid = group.uuid, members = members)
        upsertKeys(keys = keys)
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
     *
     * The change to its members pending for it goes with it, see [PendingMembershipChangeEntity]: a
     * removal of those who departed, the one change a member who may leave makes, which the leave
     * makes moot whether or not the server took it. Left, it would be sent again by every sync, from
     * a group whose MLS state the leave deletes.
     */
    @Transaction
    suspend fun markLeftAndOweLeave(groupUuid: String) {
        markLeft(uuid = groupUuid)
        insertDeclined(declined = DeclinedGroupEntity(groupUuid = groupUuid))
        deleteMembershipChange(groupUuid = groupUuid)
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

    /** Records [change] as the one pending for its group, see [PendingMembershipChangeEntity]. */
    @Upsert
    suspend fun upsertMembershipChange(change: PendingMembershipChangeEntity)

    /**
     * Records [mlsGroupId] as the MLS group of the group [uuid], or none, see [GroupEntity.mlsGroupId]:
     * what a rejoin after a log-out sets, and clears again should the server refuse it, see
     * [saveRejoin] and [forgetRejoin].
     */
    @Query("UPDATE GroupEntity SET mlsGroupId = :mlsGroupId WHERE uuid = :uuid")
    suspend fun setMlsGroupId(uuid: String, mlsGroupId: ByteArray?)

    /**
     * Records [mlsGroupId], the MLS group the library just stored as it rejoined the group of
     * [change] from outside it, as that group's, with [change], the rejoin that sends its Commit,
     * see `group.GroupRejoiner`, in one transaction: a group naming its MLS group with no rejoin
     * pending would take a state the server may never hear of for one in step with it, and a rejoin
     * pending for a group naming none would have every sync find nothing to send it from.
     */
    @Transaction
    suspend fun saveRejoin(mlsGroupId: ByteArray, change: PendingMembershipChangeEntity) {
        setMlsGroupId(uuid = change.groupUuid, mlsGroupId = mlsGroupId)
        upsertMembershipChange(change = change)
    }

    /**
     * Forgets the rejoin pending for the group [groupUuid] and the MLS group it was built in, once
     * the server refused it and the library deleted that state, see `group.GroupMembershipSender`,
     * in one transaction: the group is one brought back after a log-out again, which the next sync
     * rejoins anew, see `group.GroupRejoiner`.
     */
    @Transaction
    suspend fun forgetRejoin(groupUuid: String) {
        setMlsGroupId(uuid = groupUuid, mlsGroupId = null)
        deleteMembershipChange(groupUuid = groupUuid)
    }

    /**
     * Every Commit of the user's the server has not been heard to take, or whose taking this device
     * has yet to act on, see [PendingMembershipChangeEntity].
     */
    @Query("SELECT * FROM PendingMembershipChangeEntity")
    suspend fun getMembershipChanges(): List<PendingMembershipChangeEntity>

    /**
     * Drops the change pending for the group [groupUuid] that waits on the server, once nothing is
     * left to do about it. One to make again stays, see [PendingMembershipChangeEntity.isOutdated].
     */
    @Query("DELETE FROM PendingMembershipChangeEntity WHERE groupUuid = :groupUuid AND isOutdated = false")
    suspend fun deleteMembershipChange(groupUuid: String)

    /**
     * Records the change pending for the group [groupUuid] that waits on the server as one to make
     * again, its Commit refused for its epoch and discarded, see
     * [PendingMembershipChangeEntity.isOutdated], in place of any to make again already: the one it
     * was made again from, which a process that did not live to drop it left, see
     * `group.GroupMembershipChanger.remakeOutdatedUnderGroupsLock`.
     */
    @Transaction
    suspend fun markMembershipChangeOutdated(groupUuid: String) {
        deleteOutdatedMembershipChange(groupUuid = groupUuid)
        setMembershipChangeOutdated(groupUuid = groupUuid)
    }

    /** See [markMembershipChangeOutdated], which makes room for it first. */
    @Query(
        """UPDATE PendingMembershipChangeEntity SET isOutdated = true
        WHERE groupUuid = :groupUuid AND isOutdated = false""",
    )
    suspend fun setMembershipChangeOutdated(groupUuid: String)

    /**
     * Drops the change pending for the group [groupUuid] if it is one to make again, see
     * [PendingMembershipChangeEntity.isOutdated]: made again since, or with nothing left to make.
     */
    @Query("DELETE FROM PendingMembershipChangeEntity WHERE groupUuid = :groupUuid AND isOutdated = true")
    suspend fun deleteOutdatedMembershipChange(groupUuid: String)

    /**
     * Whether a change to the group [groupUuid] is pending: its creation, or a change to its
     * members, waiting on the server or to be made again, see [PendingMembershipChangeEntity.isOutdated].
     */
    @Query(HAS_PENDING_CHANGE)
    suspend fun hasPendingChange(groupUuid: String): Boolean

    /**
     * Whether a change to the group [groupUuid] is waiting on the server: its creation, or a change
     * to its members but one to make again, which is nowhere but on this device, see
     * [PendingMembershipChangeEntity.isOutdated].
     */
    @Query(HAS_CHANGE_ON_SERVER)
    suspend fun hasChangeOnServer(groupUuid: String): Boolean

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

    /** Records those of [keys] this device holds no key of the same generation for, see [insertRecoveredKeys]. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissingKeys(keys: List<GroupKeyEntity>)

    /**
     * Takes [keys], at least one, which the group [groupUuid] gave this device as Commits read in
     * it took it from epoch to epoch, see `group.GroupSync`, among the generations of its key, see
     * [GroupKeyEntity], and the one of the highest generation as its key, see [updateGroupKey],
     * when it is past the group's, in one transaction: the group would otherwise seal under a key
     * whose generation this device does not hold, or hold one it never seals under. A key derived
     * replaces whatever was held at its generation, MLS being what every member derives it from.
     */
    @Transaction
    suspend fun takeDerivedKeys(groupUuid: String, keys: List<GroupKeyEntity>) {
        val newest = keys.maxBy { it.generation }

        upsertKeys(keys = keys)

        val held = get(uuid = groupUuid) ?: return

        if (newest.generation > held.keyGeneration) {
            updateGroupKey(uuid = groupUuid, groupKey = newest.groupKey, keyGeneration = newest.generation)
        }
    }

    /**
     * Takes [key] as the key of its group once the server took the Commit of the user's leading to
     * its generation and this device applied it, records the group as backed up, the Commit having
     * carried the user's copy of it, see `group.AddGroupMembersBody.keyCopy`, and drops the change
     * that made it, see [PendingMembershipChangeEntity], in one transaction: a change dropped
     * without its key taken would leave the group sealing under a key of an epoch it left, while one
     * whose key is taken and that is sent again only hears it was taken already. The key it replaces
     * stays among the group's generations, see [GroupKeyEntity].
     */
    @Transaction
    suspend fun takeCommittedKey(key: GroupKeyEntity) {
        upsertKeys(keys = listOf(key))
        updateGroupKey(uuid = key.groupUuid, groupKey = key.groupKey, keyGeneration = key.generation)
        markBackedUp(uuid = key.groupUuid)
        deleteMembershipChange(groupUuid = key.groupUuid)
    }

    /**
     * Every generation of the keys of the groups the user is still in, whose MLS group this device
     * holds, that owes the server a look at its links, see [GroupKeyEntity.isLinkOwed].
     */
    @Query(
        """SELECT * FROM GroupKeyEntity WHERE isLinkOwed = true
        AND groupUuid IN (SELECT uuid FROM GroupEntity WHERE isLeft = false AND mlsGroupId IS NOT NULL)""",
    )
    suspend fun getKeysOwingLinks(): List<GroupKeyEntity>

    /**
     * Records that the server holds a link at the generation [generation] of the keys of the group
     * [groupUuid] that leads back to the key before it this device holds, see
     * [GroupKeyEntity.isLinkOwed].
     */
    @Query("UPDATE GroupKeyEntity SET isLinkOwed = false WHERE groupUuid = :groupUuid AND generation = :generation")
    suspend fun markLinkSeen(groupUuid: String, generation: Long)

    /** Records whether the group [uuid] waits for its creator's device, see [GroupEntity.isAwaitingOwner]. */
    @Query("UPDATE GroupEntity SET isAwaitingOwner = :isAwaitingOwner WHERE uuid = :uuid")
    suspend fun setAwaitingOwner(uuid: String, isAwaitingOwner: Boolean)

    /** Every generation of the key of the group [groupUuid] this device holds, see [GroupKeyEntity]. */
    @Query("SELECT * FROM GroupKeyEntity WHERE groupUuid = :groupUuid")
    suspend fun getKeys(groupUuid: String): List<GroupKeyEntity>

    /**
     * Records [keys], generations of the key of the group [groupUuid] got back through the links
     * the server keeps, see `group.GroupKeyChain`, in one transaction: none in place of a key this
     * device holds of the same generation, which MLS gave it or the user's copy held,
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
     * The signature keys of the current members of every group this device holds the MLS state of,
     * the user being [selfUuid] and left out, as their trees last held them: what the vault keeps of
     * each group for a rejoin after a log-out, see [group.MemberProfileRepository.backUp]. A group
     * this device holds no state of has no tree to read them from, so none of its keys are here:
     * one brought back after a log-out, its rejoin not settled yet included.
     */
    @Query(
        """SELECT k.* FROM GroupMemberSignatureKeyEntity k
        JOIN GroupMemberEntity m ON m.groupUuid = k.groupUuid AND m.userUuid = k.userUuid
        JOIN GroupEntity g ON g.uuid = k.groupUuid
        WHERE g.isLeft = 0 AND g.mlsGroupId IS NOT NULL AND k.userUuid != :selfUuid""",
    )
    suspend fun getHeldMemberSignatureKeys(selfUuid: String): List<GroupMemberSignatureKeyEntity>

    /**
     * The UUIDs of the groups nobody is left to vouch for: those the user is no longer in, removed,
     * left or deleted, and those this device holds the MLS state of with nobody but the user, the
     * user being [selfUuid], in it. By the rows of its members rather than of their keys, so that a
     * rejoin saved and not settled yet, whose members are the server's and whose keys are not read
     * yet, is not taken for one: what the vault holds of it is what it would start over from.
     */
    @Query(
        """SELECT uuid FROM GroupEntity g
        WHERE g.isLeft = 1 OR (
            g.mlsGroupId IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM GroupMemberEntity m WHERE m.groupUuid = g.uuid AND m.userUuid != :selfUuid)
        )""",
    )
    suspend fun getGroupsNobodyVouchesFor(selfUuid: String): List<String>

    /**
     * The groups this device holds the MLS state of that nothing recorded the keys of the members
     * of: those the user created, which nothing records until their first Commit, see
     * [GroupMemberSignatureKeyEntity], whichever build created them.
     */
    @Query(
        """SELECT * FROM GroupEntity g
        WHERE g.isLeft = 0 AND g.mlsGroupId IS NOT NULL
        AND NOT EXISTS (SELECT 1 FROM GroupMemberSignatureKeyEntity k WHERE k.groupUuid = g.uuid)""",
    )
    suspend fun getHeldWithoutSignatureKeys(): List<GroupEntity>

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
