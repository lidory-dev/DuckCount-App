package database.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import database.data.local.entity.ContactRequestEntity
import database.data.local.entity.OpenedContactRequest
import database.data.local.entity.OutgoingContactRequestEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactRequestDao {
    /**
     * How many requests are waiting for an answer.
     *
     * Opened ones only: a request nobody has read yet cannot be answered, and one that
     * turns out unreadable is refused without ever being shown.
     */
    @Query(
        """SELECT COUNT(*) FROM ContactRequestEntity
        WHERE mlsGroupId IS NOT NULL AND profileEncryptionKey IS NOT NULL""",
    )
    fun getCountAsFlow(): Flow<Int>

    /** The requests waiting for an answer, oldest first. See [getCountAsFlow]. */
    @Query(
        """SELECT uuid, senderUserUuid, mlsGroupId, profileEncryptionKey, senderFullName
        FROM ContactRequestEntity
        WHERE mlsGroupId IS NOT NULL AND profileEncryptionKey IS NOT NULL
        ORDER BY createdAt, uuid""",
    )
    fun getOpenedAsFlow(): Flow<List<OpenedContactRequest>>

    /** The same list, read once, for a caller comparing two moments rather than observing. */
    @Query(
        """SELECT uuid, senderUserUuid, mlsGroupId, profileEncryptionKey, senderFullName
        FROM ContactRequestEntity
        WHERE mlsGroupId IS NOT NULL AND profileEncryptionKey IS NOT NULL
        ORDER BY createdAt, uuid""",
    )
    suspend fun getOpenedOnce(): List<OpenedContactRequest>

    @Query(
        """SELECT uuid, senderUserUuid, mlsGroupId, profileEncryptionKey, senderFullName
        FROM ContactRequestEntity
        WHERE uuid = :uuid AND mlsGroupId IS NOT NULL AND profileEncryptionKey IS NOT NULL""",
    )
    suspend fun getOpened(uuid: String): OpenedContactRequest?

    /** The opened requests whose sender's profile has not been read yet. */
    @Query(
        """SELECT uuid, senderUserUuid, mlsGroupId, profileEncryptionKey, senderFullName
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

    /** Records a request the user sent, see [OutgoingContactRequestEntity]. */
    @Insert
    suspend fun insertOutgoing(request: OutgoingContactRequestEntity)

    /**
     * Drops the request the user sent [recipientUserUuid] in [mlsGroupId], and returns how many
     * there were: one for the answer to it, none for a card nobody asked for.
     */
    @Query(
        """DELETE FROM OutgoingContactRequestEntity
        WHERE recipientUserUuid = :recipientUserUuid AND mlsGroupId = :mlsGroupId""",
    )
    suspend fun deleteOutgoing(recipientUserUuid: String, mlsGroupId: ByteArray): Int

    /** Drops every request the user sent [recipientUserUuid], who is a contact now. */
    @Query("DELETE FROM OutgoingContactRequestEntity WHERE recipientUserUuid = :recipientUserUuid")
    suspend fun deleteOutgoingTo(recipientUserUuid: String)
}
