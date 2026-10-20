package database.data.local.dao

import database.data.local.entity.ContactEntity
import database.data.local.entity.DirectChannelEntity
import database.data.local.entity.IdentityConflictEntity
import database.data.local.entity.KeyRequestBudgetEntity
import database.data.local.entity.PendingExpenseKeyEntity
import database.data.local.entity.PendingExpenseKeyState
import database.data.local.entity.PendingProfileKeyEntity
import database.data.local.entity.ProfileKeyRequestBudgetEntity
import database.data.local.entity.UserEntity
import database.data.local.entity.VerifiableContact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The tables about the user's contacts, for the length of a test, read and written through the
 * DAOs that reach them as their queries would: the contacts, the names shown for them, who is
 * blocked, and whose profile key this device is missing.
 *
 * Only what the contact code asks of them. Anything else fails the test, as a call nobody
 * expected.
 */
internal class ContactTables {

    /** Every contact row, tombstones included, by user UUID. */
    val contacts = LinkedHashMap<String, ContactEntity>()

    /** The names shown, by user UUID. A user hidden, or never read, has none. */
    val names = mutableMapOf<String, String>()

    /** Who is blocked, see [IdentityConflictEntity]. */
    val blocked = mutableSetOf<String>()

    /** See [PendingProfileKeyEntity], by user UUID. */
    val pending = LinkedHashMap<String, PendingProfileKeyEntity>()

    /** See [ProfileKeyRequestBudgetEntity], by peer UUID. */
    val budgets = mutableMapOf<String, Long>()

    fun add(contact: ContactEntity) {
        contacts[contact.userUuid] = contact
    }

    val contactDao: ContactDao = object : ContactDao {
        override suspend fun upsert(contact: ContactEntity) = add(contact)

        override suspend fun insertNew(contacts: List<ContactEntity>) {
            contacts.forEach { this@ContactTables.contacts.getOrPut(it.userUuid) { it } }
        }

        override suspend fun get(userUuid: String): ContactEntity? = contacts[userUuid]?.takeUnless { it.isRemoved }

        override suspend fun getUserUuids(): List<String> = current().map { it.userUuid }

        override suspend fun getUnblockedUserUuids(): List<String> = unblocked().map { it.userUuid }

        override suspend fun isUnblocked(userUuid: String): Boolean = unblocked().any { it.userUuid == userUuid }

        override suspend fun getUnblocked(): List<ContactEntity> = unblocked()

        override suspend fun getNotBackedUp(): List<ContactEntity> = contacts.values.filterNot { it.isBackedUp }

        override suspend fun markBackedUp(
            userUuid: String,
            profileEncryptionKey: ByteArray,
            handedProfileKeyHash: String?,
            verifiedSafetyNumber: String?,
        ) {
            val row = get(userUuid) ?: return

            if (!row.profileEncryptionKey.contentEquals(profileEncryptionKey)) return
            if (row.handedProfileKeyHash != handedProfileKeyHash) return
            if (row.verifiedSafetyNumber != verifiedSafetyNumber) return

            add(row.copy(isBackedUp = true))
        }

        override suspend fun markRemoved(userUuid: String, rotatesProfileKey: Boolean) {
            val row = contacts[userUuid] ?: return

            add(row.copy(isBackedUp = false, isRemoved = true, rotatesProfileKey = rotatesProfileKey))
        }

        override suspend fun clearProfileKeyRotation(userUuids: List<String>) {
            userUuids.mapNotNull { contacts[it] }.forEach { add(it.copy(rotatesProfileKey = false)) }
        }

        override suspend fun replaceProfileEncryptionKey(userUuid: String, profileEncryptionKey: ByteArray) {
            val row = get(userUuid) ?: return

            add(row.copy(profileEncryptionKey = profileEncryptionKey, isBackedUp = false))
        }

        override suspend fun setHandedProfileKeyHash(userUuid: String, handedProfileKeyHash: String) {
            val row = get(userUuid) ?: return

            add(row.copy(handedProfileKeyHash = handedProfileKeyHash, isBackedUp = false))
        }

        /** The row as it stands when the flow is collected, which is all a test reads of it. */
        override fun getVerifiableAsFlow(userUuid: String): Flow<VerifiableContact?> = flow {
            emit(
                get(userUuid)?.let {
                    VerifiableContact(verifiedSafetyNumber = it.verifiedSafetyNumber, isBlocked = userUuid in blocked)
                },
            )
        }

        override suspend fun setVerifiedSafetyNumber(userUuid: String, verifiedSafetyNumber: String?) {
            val row = get(userUuid) ?: return

            add(row.copy(verifiedSafetyNumber = verifiedSafetyNumber, isBackedUp = false))
        }

        override suspend fun deleteRemoved(userUuids: List<String>) {
            userUuids.filter { contacts[it]?.isRemoved == true }.forEach { contacts.remove(it) }
        }

        override suspend fun getDueForRead(): List<ContactEntity> =
            current().filter { contact ->
                val stale = pending[contact.userUuid]

                contact.userUuid !in names ||
                    (stale != null && !stale.profileEncryptionKey.contentEquals(contact.profileEncryptionKey))
            }
    }

    val pendingProfileKeyDao: PendingProfileKeyDao = object : PendingProfileKeyDao {
        override suspend fun insertNew(userUuid: String, profileEncryptionKey: ByteArray) {
            pending.getOrPut(userUuid) {
                PendingProfileKeyEntity(
                    userUuid = userUuid,
                    profileEncryptionKey = profileEncryptionKey,
                    lastRequestedAt = null,
                )
            }
        }

        override suspend fun setReadWith(userUuid: String, profileEncryptionKey: ByteArray) {
            val row = pending[userUuid] ?: return

            pending[userUuid] = row.copy(profileEncryptionKey = profileEncryptionKey)
        }

        override suspend fun getAskable(): List<PendingProfileKeyEntity> {
            val askable = unblocked().map { it.userUuid }.toSet()

            return pending.values
                .filter { !it.isAccountGone && it.userUuid in askable }
                .sortedWith(
                    compareBy<PendingProfileKeyEntity> { it.lastRequestedAt != null }
                        .thenBy { it.lastRequestedAt }
                        .thenBy { it.userUuid },
                )
        }

        override suspend fun markRequested(userUuid: String, requestedAt: Long) {
            val row = pending[userUuid] ?: return

            pending[userUuid] = row.copy(lastRequestedAt = requestedAt, requestCount = row.requestCount + 1)
        }

        override suspend fun markAccountGone(userUuid: String) {
            val row = pending[userUuid] ?: return

            pending[userUuid] = row.copy(isAccountGone = true)
        }

        override suspend fun delete(userUuid: String) {
            pending.remove(userUuid)
        }

        override suspend fun getRequestBudgetFullAt(peerUuid: String): Long? = budgets[peerUuid]

        override suspend fun setRequestBudget(budget: ProfileKeyRequestBudgetEntity) {
            budgets[budget.peerUuid] = budget.budgetFullAt
        }
    }

    val identityConflictDao: IdentityConflictDao = object : IdentityConflictDao {
        override suspend fun insert(conflict: IdentityConflictEntity) {
            blocked += conflict.peerUuid
        }

        override suspend fun isBlocked(peerUuid: String): Boolean = peerUuid in blocked

        override suspend fun getBlocked(): List<String> = blocked.toList()
    }

    /** The names, which is all the contact code asks of the users. */
    val userDao: UserDao = object : UserDao {
        override suspend fun insert(userEntity: UserEntity) {
            names[userEntity.uuid] = userEntity.fullName
        }

        override suspend fun remove(userUuid: String) {
            names.remove(userUuid)
        }

        override suspend fun getFullName(uuid: String): String? = names[uuid]

        override suspend fun update(userEntity: UserEntity) = error("Not used here")
        override suspend fun delete(userEntity: UserEntity) = error("Not used here")
        override fun getAllAsFlow(): Flow<List<UserEntity>> = error("Not used here")
        override fun getAllFiltered(filter: String): Flow<List<UserEntity>> = error("Not used here")
        override fun getUserAsFlow(uuid: String): Flow<UserEntity?> = error("Not used here")
        override fun getUserFullNameAsFlow(uuid: String): Flow<String?> = error("Not used here")
        override fun getAllFromGroupFiltered(filter: String, groupUuid: String): Flow<List<UserEntity>> =
            error("Not used here")

        override suspend fun updateFullName(userUuid: String, fullName: String) = error("Not used here")
    }

    private fun current(): List<ContactEntity> = contacts.values.filterNot { it.isRemoved }

    private fun unblocked(): List<ContactEntity> = current().filterNot { it.userUuid in blocked }
}

/** No channel to anybody, which is how a contact restored from the vault starts out. */
internal object NoChannels : DirectChannelDao {
    override suspend fun get(peerUuid: String): DirectChannelEntity? = null

    override suspend fun clear(peerUuid: String) = Unit

    override suspend fun upsert(channel: DirectChannelEntity) = error("Not used here")
    override suspend fun markJoined(peerUuid: String) = error("Not used here")
    override suspend fun setReplyBudgetFullAt(peerUuid: String, fullAt: Long) = error("Not used here")
    override suspend fun countWelcomeAttachment(peerUuid: String) = error("Not used here")
}

/** No expense key awaited from anybody. */
internal object NothingAwaited : PendingExpenseKeyDao {
    override suspend fun markAwaitedAtRemoval(creditorUuid: String) = Unit

    override suspend fun insertNew(expenseUuid: String, creditorUuid: String, firstSeenAt: Long) =
        error("Not used here")

    override suspend fun getInState(state: PendingExpenseKeyState): List<PendingExpenseKeyEntity> =
        error("Not used here")

    override suspend fun getAwaitedAtRemoval(creditorUuid: String): List<String> = error("Not used here")
    override suspend fun markRequested(expenseUuids: List<String>, requestedAt: Long) = error("Not used here")
    override suspend fun setState(expenseUuids: List<String>, state: PendingExpenseKeyState) = error("Not used here")
    override suspend fun deleteNotIn(expenseUuids: List<String>) = error("Not used here")
    override suspend fun deleteAll() = error("Not used here")
    override suspend fun getRequestBudgetFullAt(creditorUuid: String): Long? = error("Not used here")
    override suspend fun setRequestBudget(budget: KeyRequestBudgetEntity) = error("Not used here")
}

internal fun ContactEntity.copy(
    profileEncryptionKey: ByteArray = this.profileEncryptionKey,
    handedProfileKeyHash: String? = this.handedProfileKeyHash,
    verifiedSafetyNumber: String? = this.verifiedSafetyNumber,
    isBackedUp: Boolean = this.isBackedUp,
    isRemoved: Boolean = this.isRemoved,
    rotatesProfileKey: Boolean = this.rotatesProfileKey,
) = ContactEntity(
    userUuid = userUuid,
    profileEncryptionKey = profileEncryptionKey,
    handedProfileKeyHash = handedProfileKeyHash,
    verifiedSafetyNumber = verifiedSafetyNumber,
    isBackedUp = isBackedUp,
    isRemoved = isRemoved,
    rotatesProfileKey = rotatesProfileKey,
)

internal fun PendingProfileKeyEntity.copy(
    profileEncryptionKey: ByteArray = this.profileEncryptionKey,
    lastRequestedAt: Long? = this.lastRequestedAt,
    requestCount: Int = this.requestCount,
    isAccountGone: Boolean = this.isAccountGone,
) = PendingProfileKeyEntity(
    userUuid = userUuid,
    profileEncryptionKey = profileEncryptionKey,
    lastRequestedAt = lastRequestedAt,
    requestCount = requestCount,
    isAccountGone = isAccountGone,
)
