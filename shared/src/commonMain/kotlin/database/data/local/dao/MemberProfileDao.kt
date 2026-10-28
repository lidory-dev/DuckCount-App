package database.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import database.data.local.entity.MemberProfileEntity

/** The keys and names of the members of the user's groups, see [MemberProfileEntity]. */
@Dao
interface MemberProfileDao {

    @Query("SELECT * FROM MemberProfileEntity WHERE userUuid = :userUuid")
    suspend fun get(userUuid: String): MemberProfileEntity?

    @Upsert
    suspend fun upsert(memberProfile: MemberProfileEntity)

    /** Adds the rows for users this device holds none for yet, leaving the others as they are. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(memberProfiles: List<MemberProfileEntity>)

    /**
     * Takes [profileEncryptionKey] as the key to the profile of [userUuid], from its owner, see
     * [MemberProfileEntity.isFromOwner], which came in a message the server stored at [sentAt], in
     * milliseconds since the epoch, in place of whichever was held, for the vault to take in turn.
     * The name read with the key held goes on showing until one is read with this one.
     *
     * The very key held from its owner already only has the time it came in moved up to [sentAt],
     * see [raiseOwnerKeySentAt]: a member posts their key into every group they are in, and the
     * creator writes his own into each of his, and an older key has to lose to the latest of those
     * messages, not the first one read. A Welcome or a map built before its creator's key rotated
     * and resent after it, see `group.GroupCreator` and `group.GroupMembershipChanger`, carries
     * his older key in a message stored after some of his posts of the newer one: kept to the
     * first, the stale key would win over a later post read before it. Kept to the latest, what is
     * held is the key of the latest of its owner's messages read, whatever order they were read in,
     * but for two stored at the same millisecond.
     *
     * Nothing changes either when the key held from its owner came in a message stored no earlier
     * than this one, see [MemberProfileEntity.ownerKeySentAt]: that one is the newer key, the older
     * post having been read after it, from a group read later in the pass, or in a later pass. Two
     * messages stored at the same millisecond say nothing of which of their keys is the newer, so
     * the one held stays: replacing it with one no likelier to be current would only have the
     * vault take it and the name read again, and the owner's next post settles it either way.
     *
     * A relayed key, or one from its owner the vault brought back, which came with no time, is
     * replaced whenever this one was sent, see [MemberProfileEntity.ownerKeySentAt].
     *
     * In one transaction, so that a name read in between is not lost.
     */
    @Transaction
    suspend fun takeOwnerKey(userUuid: String, profileEncryptionKey: ByteArray, sentAt: Long) {
        val held = get(userUuid = userUuid)

        if (held != null && held.isFromOwner) {
            if (held.profileEncryptionKey.contentEquals(profileEncryptionKey)) {
                return raiseOwnerKeySentAt(userUuid = userUuid, sentAt = sentAt)
            }

            if (held.ownerKeySentAt != null && held.ownerKeySentAt >= sentAt) return
        }

        upsert(
            MemberProfileEntity(
                userUuid = userUuid,
                profileEncryptionKey = profileEncryptionKey,
                isFromOwner = true,
                ownerKeySentAt = sentAt,
                fullName = held?.fullName,
                nameKeyHash = held?.nameKeyHash,
                isBackedUp = false,
            ),
        )
    }

    /**
     * Moves the time the key held for [userUuid] came in from its owner up to [sentAt], when it is
     * earlier or there is none, see [takeOwnerKey]. The key and the name stay, and so does the
     * vault's copy: the vault holds no time, see [MemberProfileEntity.ownerKeySentAt].
     */
    @Query(
        """UPDATE MemberProfileEntity SET ownerKeySentAt = :sentAt
        WHERE userUuid = :userUuid AND (ownerKeySentAt IS NULL OR ownerKeySentAt < :sentAt)""",
    )
    suspend fun raiseOwnerKeySentAt(userUuid: String, sentAt: Long)

    /**
     * Takes [profileEncryptionKey] as the key to the profile of [userUuid], relayed by the creator
     * of a group, only when this device holds none for them: the creator may hold a key its owner
     * replaced since, see [MemberProfileEntity.isFromOwner], and nothing here tells which of the
     * two is newer. One held that is older is replaced by the owner's own post, which every member
     * makes into each group they join, and again as its creator seats somebody, see
     * `group.GroupSync.takeProfileKeys`.
     */
    suspend fun fillGap(userUuid: String, profileEncryptionKey: ByteArray) {
        insertNew(
            listOf(
                MemberProfileEntity(
                    userUuid = userUuid,
                    profileEncryptionKey = profileEncryptionKey,
                    isFromOwner = false,
                    ownerKeySentAt = null,
                    fullName = null,
                    nameKeyHash = null,
                    isBackedUp = false,
                ),
            ),
        )
    }

    /** The key to the profile of [userUuid] this device learned through the groups, or null when none. */
    @Query("SELECT profileEncryptionKey FROM MemberProfileEntity WHERE userUuid = :userUuid")
    suspend fun getKey(userUuid: String): ByteArray?

    /**
     * Every row but those of the user's contacts, removed ones aside: what names the members who
     * are not their contacts, whose names the contact sync reads, see
     * [ContactDao.getDueForRead].
     */
    @Query(
        """SELECT * FROM MemberProfileEntity
        WHERE userUuid NOT IN (SELECT userUuid FROM ContactEntity WHERE isRemoved = false)""",
    )
    suspend fun getNotContacts(): List<MemberProfileEntity>

    /**
     * Saves [fullName] as the name of [userUuid], read with [profileEncryptionKey], whose partial
     * hash is [nameKeyHash], only while that is still the key held: one that replaced it during the
     * read is read in turn by the next pass. A name that changed is for the vault to take.
     */
    @Query(
        """UPDATE MemberProfileEntity
        SET fullName = :fullName,
        nameKeyHash = :nameKeyHash,
        isBackedUp = CASE WHEN fullName IS :fullName THEN isBackedUp ELSE false END
        WHERE userUuid = :userUuid AND profileEncryptionKey = :profileEncryptionKey""",
    )
    suspend fun setName(userUuid: String, profileEncryptionKey: ByteArray, fullName: String, nameKeyHash: String)

    /**
     * Records the read of the profile of [userUuid] under [profileEncryptionKey], whose partial
     * hash is [nameKeyHash], as settled without a name, the server holding nothing under it, while
     * that is still the key held: whatever name was read before stays.
     */
    @Query(
        """UPDATE MemberProfileEntity SET nameKeyHash = :nameKeyHash
        WHERE userUuid = :userUuid AND profileEncryptionKey = :profileEncryptionKey""",
    )
    suspend fun settleRead(userUuid: String, profileEncryptionKey: ByteArray, nameKeyHash: String)

    /**
     * Takes [fullName], the name the contact list showed for [userUuid] as they stopped being the
     * user's contact, as the one their row names them by, when there is one, and has their profile
     * read again with the key held, see [MemberProfileEntity.nameKeyHash].
     *
     * Nothing reads the row while they are a contact, so its name can be one read before they
     * became one, and its read marker still match the key: the contact list's name is the latest
     * read, and it goes on showing for them should the read find nothing, their account deleted
     * say. A name that changed is for the vault to take.
     */
    @Query(
        """UPDATE MemberProfileEntity
        SET fullName = COALESCE(:fullName, fullName),
        nameKeyHash = NULL,
        isBackedUp = CASE WHEN fullName IS COALESCE(:fullName, fullName) THEN isBackedUp ELSE false END
        WHERE userUuid = :userUuid""",
    )
    suspend fun takeFormerContactName(userUuid: String, fullName: String?)

    /** Every row the vault does not hold as it is on this device. */
    @Query("SELECT * FROM MemberProfileEntity WHERE isBackedUp = false")
    suspend fun getNotBackedUp(): List<MemberProfileEntity>

    /**
     * Records that the vault holds the row of [userUuid], only while it is still as it was written
     * there: one that changed during the write stays for the next one.
     */
    @Query(
        """UPDATE MemberProfileEntity SET isBackedUp = true
        WHERE userUuid = :userUuid
        AND profileEncryptionKey = :profileEncryptionKey
        AND isFromOwner = :isFromOwner
        AND fullName IS :fullName""",
    )
    suspend fun markBackedUp(userUuid: String, profileEncryptionKey: ByteArray, isFromOwner: Boolean, fullName: String?)
}
