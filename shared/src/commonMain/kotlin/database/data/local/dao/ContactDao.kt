package database.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import database.data.local.entity.ContactEntity

@Dao
interface ContactDao {
    @Upsert
    suspend fun upsert(contact: ContactEntity)

    /** Adds the contacts this device does not hold yet, leaving the others as they are. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(contacts: List<ContactEntity>)

    /** The contact [userUuid], or null when they are not, or no longer, one. */
    @Query("SELECT * FROM ContactEntity WHERE userUuid = :userUuid AND isRemoved = false")
    suspend fun get(userUuid: String): ContactEntity?

    /** Who the user's contacts are, removed ones aside. */
    @Query("SELECT userUuid FROM ContactEntity WHERE isRemoved = false")
    suspend fun getUserUuids(): List<String>

    /**
     * Who the user hears from: their contacts, removed and blocked ones aside, see
     * [IdentityConflictEntity][database.data.local.entity.IdentityConflictEntity].
     */
    @Query(
        """SELECT userUuid FROM ContactEntity
        WHERE isRemoved = false
        AND userUuid NOT IN (SELECT peerUuid FROM IdentityConflictEntity)""",
    )
    suspend fun getUnblockedUserUuids(): List<String>

    /** Whether [userUuid] is one of the user's contacts, removed and blocked ones aside. */
    @Query(
        """SELECT EXISTS (
            SELECT 1 FROM ContactEntity
            WHERE userUuid = :userUuid AND isRemoved = false
            AND userUuid NOT IN (SELECT peerUuid FROM IdentityConflictEntity)
        )""",
    )
    suspend fun isUnblocked(userUuid: String): Boolean

    /** The same contacts, whole, for a caller that hands them something. */
    @Query(
        """SELECT * FROM ContactEntity
        WHERE isRemoved = false
        AND userUuid NOT IN (SELECT peerUuid FROM IdentityConflictEntity)""",
    )
    suspend fun getUnblocked(): List<ContactEntity>

    /** Every change the vault has not taken yet, additions and removals alike. */
    @Query("SELECT * FROM ContactEntity WHERE isBackedUp = false")
    suspend fun getNotBackedUp(): List<ContactEntity>

    /**
     * Records that the vault holds the contact [userUuid] as they were written to it, with
     * [profileEncryptionKey] and [handedProfileKeyHash].
     *
     * Unless the row changed in the meantime: one removed has its removal still to reach the
     * vault, and one handed a new key, theirs or the user's, has that still to reach it.
     */
    @Query(
        """UPDATE ContactEntity SET isBackedUp = true
        WHERE userUuid = :userUuid AND isRemoved = false
        AND profileEncryptionKey = :profileEncryptionKey
        AND handedProfileKeyHash IS :handedProfileKeyHash""",
    )
    suspend fun markBackedUp(userUuid: String, profileEncryptionKey: ByteArray, handedProfileKeyHash: String?)

    /**
     * Records that the user no longer has the contact [userUuid], until the vault no longer
     * holds them either, and whether that waits for the user's profile key to rotate, see
     * [ContactEntity.rotatesProfileKey].
     */
    @Query(
        """UPDATE ContactEntity
        SET isRemoved = true, isBackedUp = false, rotatesProfileKey = :rotatesProfileKey
        WHERE userUuid = :userUuid""",
    )
    suspend fun markRemoved(userUuid: String, rotatesProfileKey: Boolean)

    /**
     * Records that the removals of the contacts in [userUuids] no longer wait for the user's
     * profile key to rotate, once it has, see [ContactEntity.rotatesProfileKey].
     */
    @Query("UPDATE ContactEntity SET rotatesProfileKey = false WHERE userUuid IN (:userUuids)")
    suspend fun clearProfileKeyRotation(userUuids: List<String>)

    /**
     * Takes [profileEncryptionKey] as the key to the profile of the contact [userUuid], for the
     * vault to take in turn. Nobody else's: one who is not, or no longer, a contact is left
     * alone.
     */
    @Query(
        """UPDATE ContactEntity SET profileEncryptionKey = :profileEncryptionKey, isBackedUp = false
        WHERE userUuid = :userUuid AND isRemoved = false""",
    )
    suspend fun replaceProfileEncryptionKey(userUuid: String, profileEncryptionKey: ByteArray)

    /**
     * Records that the contact [userUuid] was handed the user's profile key whose partial hash
     * is [handedProfileKeyHash], for the vault to take in turn, see
     * [ContactEntity.handedProfileKeyHash]. One removed in the meantime is left alone, a
     * tombstone hands nothing on.
     */
    @Query(
        """UPDATE ContactEntity SET handedProfileKeyHash = :handedProfileKeyHash, isBackedUp = false
        WHERE userUuid = :userUuid AND isRemoved = false""",
    )
    suspend fun setHandedProfileKeyHash(userUuid: String, handedProfileKeyHash: String)

    /**
     * Drops the tombstones of the contacts in [userUuids], once the vault no longer holds
     * them.
     *
     * One added back in the meantime is left alone, its addition still has to reach the
     * vault.
     */
    @Query("DELETE FROM ContactEntity WHERE userUuid IN (:userUuids) AND isRemoved = true")
    suspend fun deleteRemoved(userUuids: List<String>)

    /**
     * The contacts whose profile is due a read: the ones this device shows no name for, their
     * profile never read or their user hidden when an earlier removal of them went through, and
     * the ones whose current key it is missing, see
     * [PendingProfileKeyEntity][database.data.local.entity.PendingProfileKeyEntity], once a key
     * other than the one last read with has come in.
     */
    @Query(
        """SELECT * FROM ContactEntity
        WHERE isRemoved = false
        AND (
            userUuid NOT IN (SELECT uuid FROM UserEntity WHERE isDeleted = false)
            OR EXISTS (
                SELECT 1 FROM PendingProfileKeyEntity AS pending
                WHERE pending.userUuid = ContactEntity.userUuid
                AND pending.profileEncryptionKey != ContactEntity.profileEncryptionKey
            )
        )""",
    )
    suspend fun getDueForRead(): List<ContactEntity>
}
