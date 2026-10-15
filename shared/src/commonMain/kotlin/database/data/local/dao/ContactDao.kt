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

    /** Every change the vault has not taken yet, additions and removals alike. */
    @Query("SELECT * FROM ContactEntity WHERE isBackedUp = false")
    suspend fun getNotBackedUp(): List<ContactEntity>

    /**
     * Records that the vault holds the contacts in [userUuids].
     *
     * One removed in the meantime is left alone, its removal still has to reach the vault.
     */
    @Query("UPDATE ContactEntity SET isBackedUp = true WHERE userUuid IN (:userUuids) AND isRemoved = false")
    suspend fun markBackedUp(userUuids: List<String>)

    /**
     * Records that the user no longer has the contact [userUuid], until the vault no longer
     * holds them either.
     */
    @Query("UPDATE ContactEntity SET isRemoved = true, isBackedUp = false WHERE userUuid = :userUuid")
    suspend fun markRemoved(userUuid: String)

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
     * The contacts this device shows no name for: their profile has never been read, or
     * their user was hidden when an earlier removal of them went through.
     */
    @Query(
        """SELECT * FROM ContactEntity
        WHERE isRemoved = false
        AND userUuid NOT IN (SELECT uuid FROM UserEntity WHERE isDeleted = false)""",
    )
    suspend fun getWithoutName(): List<ContactEntity>
}
