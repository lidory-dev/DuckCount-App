package database.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import database.data.local.entity.ContactRequestEntity
import database.data.local.entity.ContactStanding
import database.data.local.entity.OpenedContactRequest
import database.data.local.entity.OutgoingContactRequestEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactRequestDao {
    /**
     * How many requests are waiting for an answer.
     *
     * Opened ones only: a request nobody has read yet cannot be answered, and one that
     * turns out unreadable is refused without ever being shown. Nor one from somebody the user
     * blocked, see [BlockedUserEntity][database.data.local.entity.BlockedUserEntity]: the
     * refresh refuses it, and until then, a request the user just blocked its sender from among
     * them, it is gone from the user's sight all the same.
     */
    @Query(
        """SELECT COUNT(*) FROM ContactRequestEntity
        WHERE mlsGroupId IS NOT NULL AND profileEncryptionKey IS NOT NULL
        AND senderUserUuid NOT IN (SELECT userUuid FROM BlockedUserEntity WHERE isUnblocked = 0)""",
    )
    fun getCountAsFlow(): Flow<Int>

    /** The requests waiting for an answer, oldest first. See [getCountAsFlow]. */
    @Query(
        """SELECT uuid, senderUserUuid, mlsGroupId, profileEncryptionKey, senderFullName, isAcceptanceSent,
        handedProfileKeyHash
        FROM ContactRequestEntity
        WHERE mlsGroupId IS NOT NULL AND profileEncryptionKey IS NOT NULL
        AND senderUserUuid NOT IN (SELECT userUuid FROM BlockedUserEntity WHERE isUnblocked = 0)
        ORDER BY createdAt, uuid""",
    )
    fun getOpenedAsFlow(): Flow<List<OpenedContactRequest>>

    /** The same list, read once, for a caller that is not observing it. */
    @Query(
        """SELECT uuid, senderUserUuid, mlsGroupId, profileEncryptionKey, senderFullName, isAcceptanceSent,
        handedProfileKeyHash
        FROM ContactRequestEntity
        WHERE mlsGroupId IS NOT NULL AND profileEncryptionKey IS NOT NULL
        ORDER BY createdAt, uuid""",
    )
    suspend fun getOpenedOnce(): List<OpenedContactRequest>

    @Query(
        """SELECT uuid, senderUserUuid, mlsGroupId, profileEncryptionKey, senderFullName, isAcceptanceSent,
        handedProfileKeyHash
        FROM ContactRequestEntity
        WHERE uuid = :uuid AND mlsGroupId IS NOT NULL AND profileEncryptionKey IS NOT NULL""",
    )
    suspend fun getOpened(uuid: String): OpenedContactRequest?

    /** The opened requests whose sender's profile has not been read yet. */
    @Query(
        """SELECT uuid, senderUserUuid, mlsGroupId, profileEncryptionKey, senderFullName, isAcceptanceSent,
        handedProfileKeyHash
        FROM ContactRequestEntity
        WHERE mlsGroupId IS NOT NULL AND profileEncryptionKey IS NOT NULL AND senderFullName IS NULL""",
    )
    suspend fun getOpenedWithoutName(): List<OpenedContactRequest>

    @Query("SELECT * FROM ContactRequestEntity WHERE mlsGroupId IS NULL ORDER BY createdAt, uuid")
    suspend fun getUnopened(): List<ContactRequestEntity>

    @Query("SELECT * FROM ContactRequestEntity")
    suspend fun getAll(): List<ContactRequestEntity>

    @Query("SELECT * FROM ContactRequestEntity WHERE uuid = :uuid")
    suspend fun get(uuid: String): ContactRequestEntity?

    /** Adds the requests this device does not hold yet, leaving the others as they are. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(contactRequests: List<ContactRequestEntity>)

    @Query("DELETE FROM ContactRequestEntity WHERE uuid IN (:uuids)")
    suspend fun delete(uuids: List<String>)

    @Query(
        """UPDATE ContactRequestEntity
        SET mlsGroupId = :mlsGroupId, profileEncryptionKey = :profileEncryptionKey
        WHERE uuid = :uuid""",
    )
    suspend fun markOpened(uuid: String, mlsGroupId: ByteArray, profileEncryptionKey: ByteArray)

    @Query("UPDATE ContactRequestEntity SET senderFullName = :senderFullName WHERE uuid = :uuid")
    suspend fun setSenderFullName(uuid: String, senderFullName: String)

    /** See [ContactRequestEntity.isAcceptanceSent] and [ContactRequestEntity.handedProfileKeyHash]. */
    @Query(
        """UPDATE ContactRequestEntity SET isAcceptanceSent = 1, handedProfileKeyHash = :handedProfileKeyHash
        WHERE uuid = :uuid""",
    )
    suspend fun markAcceptanceSent(uuid: String, handedProfileKeyHash: String?)

    /** For an acceptance the server provably did not take, which handed nothing over either. */
    @Query("UPDATE ContactRequestEntity SET isAcceptanceSent = 0, handedProfileKeyHash = NULL WHERE uuid = :uuid")
    suspend fun clearAcceptanceSent(uuid: String)

    /** The request [senderUserUuid] sent with [mlsGroupId], when the user's acceptance of it went out. */
    @Query(
        """SELECT uuid, senderUserUuid, mlsGroupId, profileEncryptionKey, senderFullName, isAcceptanceSent,
        handedProfileKeyHash
        FROM ContactRequestEntity
        WHERE senderUserUuid = :senderUserUuid AND mlsGroupId = :mlsGroupId
        AND profileEncryptionKey IS NOT NULL AND isAcceptanceSent = 1""",
    )
    suspend fun getAccepted(senderUserUuid: String, mlsGroupId: ByteArray): OpenedContactRequest?

    /**
     * Server-authoritative reconciliation, in one transaction: drops the requests in
     * [vanishedUuids], the ones the server no longer lists, and adds the [listed] ones
     * this device does not hold yet.
     *
     * A request already held is left as it is rather than overwritten with the server's
     * copy, which knows nothing about it having been opened. Deleting only what vanished,
     * rather than emptying the table first, is what keeps the count from flickering
     * through zero on every refresh.
     */
    @Transaction
    suspend fun reconcile(vanishedUuids: List<String>, listed: List<ContactRequestEntity>) {
        delete(uuids = vanishedUuids)
        insertNew(contactRequests = listed)
    }

    /** The requests the user has out. */
    @Query("SELECT * FROM OutgoingContactRequestEntity")
    suspend fun getOutgoing(): List<OutgoingContactRequestEntity>

    /**
     * The requests the user has out, those to blocked recipients aside, see
     * [IdentityConflictEntity][database.data.local.entity.IdentityConflictEntity].
     */
    @Query(
        """SELECT * FROM OutgoingContactRequestEntity
        WHERE recipientUserUuid NOT IN (SELECT peerUuid FROM IdentityConflictEntity)""",
    )
    suspend fun getUnblockedOutgoing(): List<OutgoingContactRequestEntity>

    /**
     * Whether the user has a request out to [recipientUserUuid] that the server is known to hold,
     * see [OutgoingContactRequestEntity.isStored].
     */
    @Query(
        """SELECT EXISTS (
            SELECT 1 FROM OutgoingContactRequestEntity WHERE recipientUserUuid = :recipientUserUuid AND isStored = 1
        )""",
    )
    suspend fun hasStoredOutgoingTo(recipientUserUuid: String): Boolean

    /**
     * What the tables say of [userUuid], see [ContactStanding]: a contact, removed ones aside, as
     * [ContactDao.get] reads one; a request of theirs waiting for the user's answer, opened, and
     * from nobody the user blocked, as [getOpenedAsFlow] lists them; and one of the user's out to
     * them that the server is known to hold, see [OutgoingContactRequestEntity.isStored].
     *
     * In one query, so that the three are read together, never one of them as it stood before a
     * write and another as it stands after. A change made in several writes is still seen between
     * them, so an acceptance, the user's or theirs, adds them as a contact before it takes their
     * requests along, and they read as a contact throughout. One row whatever the tables hold, an
     * empty table included, as a log-out leaves every one of them.
     */
    @Query(
        """SELECT
        EXISTS (SELECT 1 FROM ContactEntity WHERE userUuid = :userUuid AND isRemoved = false) AS isContact,
        EXISTS (
            SELECT 1 FROM ContactRequestEntity
            WHERE senderUserUuid = :userUuid AND mlsGroupId IS NOT NULL AND profileEncryptionKey IS NOT NULL
            AND senderUserUuid NOT IN (SELECT userUuid FROM BlockedUserEntity WHERE isUnblocked = 0)
        ) AS hasRequestFrom,
        EXISTS (
            SELECT 1 FROM OutgoingContactRequestEntity WHERE recipientUserUuid = :userUuid AND isStored = 1
        ) AS hasRequestTo""",
    )
    fun getStandingAsFlow(userUuid: String): Flow<ContactStanding>

    /** Records a request the user sent, see [OutgoingContactRequestEntity]. */
    @Insert
    suspend fun insertOutgoing(request: OutgoingContactRequestEntity)

    /**
     * The request the user sent [recipientUserUuid] in [mlsGroupId], which a card from them in
     * that group answers, or null for a card nobody asked for.
     */
    @Query(
        """SELECT * FROM OutgoingContactRequestEntity
        WHERE recipientUserUuid = :recipientUserUuid AND mlsGroupId = :mlsGroupId""",
    )
    suspend fun getOutgoing(recipientUserUuid: String, mlsGroupId: ByteArray): OutgoingContactRequestEntity?

    /**
     * Marks the request the user sent [recipientUserUuid] in [mlsGroupId] as one the server
     * stored, see [OutgoingContactRequestEntity.isStored].
     */
    @Query(
        """UPDATE OutgoingContactRequestEntity SET isStored = 1
        WHERE recipientUserUuid = :recipientUserUuid AND mlsGroupId = :mlsGroupId""",
    )
    suspend fun markStored(recipientUserUuid: String, mlsGroupId: ByteArray)

    /**
     * Marks every request the user sent [recipientUserUuid] as one the server may hold, the
     * server having refused another as already pending, see [OutgoingContactRequestEntity.isStored].
     */
    @Query("UPDATE OutgoingContactRequestEntity SET isStored = 1 WHERE recipientUserUuid = :recipientUserUuid")
    suspend fun markStoredTo(recipientUserUuid: String)

    /** Drops the request the user sent [recipientUserUuid] in [mlsGroupId]. */
    @Query(
        """DELETE FROM OutgoingContactRequestEntity
        WHERE recipientUserUuid = :recipientUserUuid AND mlsGroupId = :mlsGroupId""",
    )
    suspend fun deleteOutgoing(recipientUserUuid: String, mlsGroupId: ByteArray)

    /**
     * Drops every request the user sent [recipientUserUuid], who is a contact now. Their groups
     * stay, see [OutgoingContactRequestEntity].
     */
    @Query("DELETE FROM OutgoingContactRequestEntity WHERE recipientUserUuid = :recipientUserUuid")
    suspend fun deleteOutgoingTo(recipientUserUuid: String)
}
