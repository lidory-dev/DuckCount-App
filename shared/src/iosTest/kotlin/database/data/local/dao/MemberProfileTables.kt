package database.data.local.dao

import database.data.local.entity.MemberProfileEntity

/**
 * The keys and names the user's groups handed over, see [MemberProfileEntity], for the length of a
 * test, read and written through the DAO as its queries would. Who is a contact is [contacts]'s
 * word, as `ContactEntity` is the query's.
 *
 * The two writes that decide between keys, [MemberProfileDao.takeOwnerKey] and
 * [MemberProfileDao.fillGap], are the DAO's own, run on top of these.
 */
internal class MemberProfileTables(private val contacts: ContactTables = ContactTables()) {

    /** Every row, by user UUID. */
    val rows = LinkedHashMap<String, MemberProfileEntity>()

    fun add(row: MemberProfileEntity) {
        rows[row.userUuid] = row
    }

    val memberProfileDao: MemberProfileDao = object : MemberProfileDao {
        override suspend fun get(userUuid: String): MemberProfileEntity? = rows[userUuid]

        override suspend fun upsert(memberProfile: MemberProfileEntity) = add(memberProfile)

        override suspend fun insertNew(memberProfiles: List<MemberProfileEntity>) {
            memberProfiles.forEach { rows.getOrPut(it.userUuid) { it } }
        }

        override suspend fun raiseOwnerKeySentAt(userUuid: String, sentAt: Long) {
            val row = rows[userUuid] ?: return
            val held = row.ownerKeySentAt

            if (held == null || held < sentAt) add(row.copy(ownerKeySentAt = sentAt))
        }

        override suspend fun getKey(userUuid: String): ByteArray? = rows[userUuid]?.profileEncryptionKey

        override suspend fun getNotContacts(): List<MemberProfileEntity> {
            val current = contacts.contacts.values.filterNot { it.isRemoved }.map { it.userUuid }.toSet()

            return rows.values.filterNot { it.userUuid in current }
        }

        override suspend fun setName(
            userUuid: String,
            profileEncryptionKey: ByteArray,
            fullName: String,
            nameKeyHash: String,
        ) {
            val row = rows[userUuid]?.takeIf { it.profileEncryptionKey.contentEquals(profileEncryptionKey) } ?: return

            add(
                row.copy(
                    fullName = fullName,
                    nameKeyHash = nameKeyHash,
                    isBackedUp = row.isBackedUp && row.fullName == fullName,
                ),
            )
        }

        override suspend fun settleRead(userUuid: String, profileEncryptionKey: ByteArray, nameKeyHash: String) {
            val row = rows[userUuid]?.takeIf { it.profileEncryptionKey.contentEquals(profileEncryptionKey) } ?: return

            add(row.copy(nameKeyHash = nameKeyHash))
        }

        override suspend fun takeFormerContactName(userUuid: String, fullName: String?) {
            val row = rows[userUuid] ?: return
            val named = fullName ?: row.fullName

            add(row.copy(fullName = named, nameKeyHash = null, isBackedUp = row.isBackedUp && row.fullName == named))
        }

        override suspend fun getNotBackedUp(): List<MemberProfileEntity> = rows.values.filterNot { it.isBackedUp }

        override suspend fun markBackedUp(
            userUuid: String,
            profileEncryptionKey: ByteArray,
            isFromOwner: Boolean,
            fullName: String?,
        ) {
            val row = rows[userUuid] ?: return

            if (!row.profileEncryptionKey.contentEquals(profileEncryptionKey)) return
            if (row.isFromOwner != isFromOwner) return
            if (row.fullName != fullName) return

            add(row.copy(isBackedUp = true))
        }
    }
}

internal fun MemberProfileEntity.copy(
    profileEncryptionKey: ByteArray = this.profileEncryptionKey,
    isFromOwner: Boolean = this.isFromOwner,
    ownerKeySentAt: Long? = this.ownerKeySentAt,
    fullName: String? = this.fullName,
    nameKeyHash: String? = this.nameKeyHash,
    isBackedUp: Boolean = this.isBackedUp,
) = MemberProfileEntity(
    userUuid = userUuid,
    profileEncryptionKey = profileEncryptionKey,
    isFromOwner = isFromOwner,
    ownerKeySentAt = ownerKeySentAt,
    fullName = fullName,
    nameKeyHash = nameKeyHash,
    isBackedUp = isBackedUp,
)
