package group

import authenticationUtils.clearMemberProfilesToRestore
import authenticationUtils.getAuthenticatedUserUuid
import authenticationUtils.getBackedUpMemberKeys
import authenticationUtils.hasMemberProfilesToRestore
import authenticationUtils.setBackedUpMemberKeys
import database.data.local.dao.GroupDao
import database.data.local.dao.MemberProfileDao
import database.data.local.entity.GroupMemberSignatureKeyEntity
import database.data.local.entity.MemberProfileEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import profile.ProfileError
import profile.ProfileRepository
import uniffi.lib_duckcount.profileEncryptionKeyPartialHash
import util.EmptyResult
import util.Result
import vault.MemberProfileBook
import vault.MemberProfileVault
import vault.VaultError
import vault.VaultMemberProfile
import kotlin.io.encoding.Base64

/**
 * The names of the members of the user's groups who are not their contacts: the keys to their
 * profiles this device learned through the groups, see [MemberProfileEntity], the names read with
 * them, see [readNames], and the vault's copy of both, see [backUp] and [restore]. Names are read
 * only for those who are not contacts, the contact sync reading the others', but every key a group
 * handed over is kept, and backed up, a contact's included: it names them should the two of them
 * part, and it is the key the user relays for them, as the creator of a group, once they are no
 * longer a contact, see [keyFor].
 *
 * A member who is no contact hands the user nothing over a channel of their own. The creator of a
 * group seated every member as a contact of his, so he holds the key of each, and hands them to
 * the others with the Welcome that seats them, and to those already in the group with each
 * addition: the key relayed that way fills in for a member nothing is held for, see [fillGap]. A
 * member posts their own into every group they join, again each time its creator seats somebody,
 * and into each once their key rotates, which is theirs as it stands and replaces whatever is
 * held but one of theirs posted later, see [takeOwnerKey]. [GroupSync] takes neither from a group
 * that cannot vouch for its members, nor from anybody but the creator, or the owner for their own.
 *
 * Whoever hands a key over, it opens nothing but its owner's own profile, see `ProfileRepository`:
 * a creator relaying the wrong one, or a server serving one member's profile as another's, leaves
 * the member unnamed, never named as somebody else. Names are their owners' word about themselves,
 * as every name is: anybody can write any name under their own UUID, and nobody under somebody
 * else's.
 *
 * The vault's copy also keeps the signature keys of each group's members, see [backUp], which come
 * back only to vouch for a rejoin, see [GroupRejoiner].
 *
 * [readFullName] is [ProfileRepository.getFullName], [isToRestore] and [markRestored] are
 * [hasMemberProfilesToRestore] and [clearMemberProfilesToRestore], [heldMemberKeys] and
 * [groupsToForget] are [GroupDao.getHeldMemberSignatureKeys] and
 * [GroupDao.getGroupsNobodyVouchesFor], for the user, and
 * [backedUpMemberKeys] and [markMemberKeysBackedUp] are [getBackedUpMemberKeys] and
 * [setBackedUpMemberKeys], or what a test stands in for any of them.
 */
class MemberProfileRepository internal constructor(
    private val memberProfileDao: MemberProfileDao,
    private val memberProfileVault: MemberProfileVault,
    private val readFullName: suspend (userUuid: String, profileEncryptionKey: ByteArray) -> Result<String, ProfileError>,
    private val isToRestore: suspend () -> Boolean,
    private val markRestored: () -> Unit,
    private val heldMemberKeys: suspend () -> List<GroupMemberSignatureKeyEntity>,
    private val groupsToForget: suspend () -> List<String>,
    private val backedUpMemberKeys: suspend () -> Int?,
    private val markMemberKeysBackedUp: suspend (digest: Int) -> Unit,
) {
    constructor(
        memberProfileDao: MemberProfileDao,
        memberProfileVault: MemberProfileVault,
        profileRepository: ProfileRepository,
        groupDao: GroupDao,
    ) : this(
        memberProfileDao = memberProfileDao,
        memberProfileVault = memberProfileVault,
        readFullName = profileRepository::getFullName,
        isToRestore = ::hasMemberProfilesToRestore,
        markRestored = ::clearMemberProfilesToRestore,
        heldMemberKeys = {
            getAuthenticatedUserUuid()?.let { groupDao.getHeldMemberSignatureKeys(selfUuid = it) }.orEmpty()
        },
        groupsToForget = {
            getAuthenticatedUserUuid()?.let { groupDao.getGroupsNobodyVouchesFor(selfUuid = it) }.orEmpty()
        },
        backedUpMemberKeys = ::getBackedUpMemberKeys,
        markMemberKeysBackedUp = ::setBackedUpMemberKeys,
    )

    /**
     * Serializes [backUp]. The members are sealed whole, so two writes from this device would
     * conflict, and the second one would only read and merge again.
     */
    private val backUpMutex = Mutex()

    /**
     * Serializes [restore], so that a second caller waits for the members the first one brings
     * back rather than reading the vault again.
     */
    private val restoreMutex = Mutex()

    /**
     * Takes [profileEncryptionKey] as the key to the profile of [userUuid], handed over by its
     * owner in a message the server stored at [sentAt], in milliseconds since the epoch: their own
     * post into a group, or the creator's own entry in what he hands over, see
     * [MemberProfileDao.takeOwnerKey]. It replaces whichever key was held, relayed or not, but one
     * of theirs that came in a message stored no earlier, which is the newer, and the name read
     * with the one it replaces goes on showing until [readNames] reads one with this one.
     *
     * Only for somebody other than the user, whose own key no group needs to hand them.
     */
    suspend fun takeOwnerKey(userUuid: String, profileEncryptionKey: ByteArray, sentAt: Long) {
        memberProfileDao.takeOwnerKey(userUuid = userUuid, profileEncryptionKey = profileEncryptionKey, sentAt = sentAt)
    }

    /**
     * Takes [profileEncryptionKey] as the key to the profile of [userUuid], relayed by the creator
     * of a group, only when this device holds none for them, see [MemberProfileDao.fillGap]: the
     * creator may hold a key its owner replaced since, which must never replace the one its owner
     * posted. One held that is older, the owner's own post replaces once they join the group, or
     * once its creator seats somebody, see [takeOwnerKey].
     *
     * Only for somebody other than the user, whose own key no group needs to hand them.
     */
    suspend fun fillGap(userUuid: String, profileEncryptionKey: ByteArray) {
        memberProfileDao.fillGap(userUuid = userUuid, profileEncryptionKey = profileEncryptionKey)
    }

    /**
     * The key to the profile of [userUuid] this device learned through the groups, or null when it
     * learned none: what the creator of a group hands over for a member who is no longer his
     * contact.
     */
    suspend fun keyFor(userUuid: String): ByteArray? = memberProfileDao.getKey(userUuid = userUuid)

    /**
     * Reads the profile of every member this device has a key for and has not read under it yet,
     * see [MemberProfileEntity.nameKeyHash], saving the name on it.
     *
     * Not a contact's, removed ones aside: the contact sync reads those, with the key the contact
     * handed over themselves, see
     * [ContactRepository.resolveNames][database.data.repository.ContactRepository.resolveNames], and
     * the contact list names them.
     *
     * A name is saved only while the key it was read with is still the one held, see
     * [MemberProfileDao.setName]: one that replaced it during the read is read in turn by the next
     * pass. A key that opens nothing on the server any more, its owner having deleted their account
     * or rotated their key ten times since, settles the read all the same, see
     * [MemberProfileDao.settleRead], and whatever name was read before stays: there is nothing
     * newer to read until another key comes in.
     *
     * Runs outside the lock the groups are synced under: it reads only what the groups already
     * handed over, and a read is a request per member.
     *
     * Every member is attempted, and the first profile that could not be asked for is reported.
     * One that does not open with the key it was asked for under is read again by the next pass.
     */
    suspend fun readNames(): EmptyResult<GroupSyncError> {
        var failure: GroupSyncError? = null

        for (member in memberProfileDao.getNotContacts()) {
            val keyHash = profileEncryptionKeyPartialHash(member.profileEncryptionKey)

            if (member.nameKeyHash == keyHash) continue

            when (val read = readFullName(member.userUuid, member.profileEncryptionKey)) {
                is Result.Success -> memberProfileDao.setName(
                    userUuid = member.userUuid,
                    profileEncryptionKey = member.profileEncryptionKey,
                    fullName = read.data,
                    nameKeyHash = keyHash,
                )

                is Result.Error -> when (val error = read.error) {
                    ProfileError.NotStored -> memberProfileDao.settleRead(
                        userUuid = member.userUuid,
                        profileEncryptionKey = member.profileEncryptionKey,
                        nameKeyHash = keyHash,
                    )

                    // One member's profile failing says nothing about the next one's.
                    is ProfileError.Network -> failure = failure ?: GroupSyncError.Network(error.error)

                    // Their owner's to put right, or a server serving somebody else's: tried again.
                    else -> Unit
                }
            }
        }

        return failure?.let { Result.Error(it) } ?: Result.Success(Unit)
    }

    /**
     * Carries to the vault every key and name it does not hold as this device does, merged with
     * what it holds, see [MemberProfileBook.with].
     *
     * The members are sealed whole, and written against the version they were read at, see
     * [MemberProfileVault.updateMemberProfiles]: a write another device made in between is read and
     * merged again, a few times at most, and otherwise left for the next backup.
     *
     * Each row is then recorded as backed up, but one that changed during the write, see
     * [MemberProfileDao.markBackedUp]. A relayed key the vault already holds one from its owner in
     * place of is too: the vault holds better, and the row would otherwise be written again by
     * every backup, for nothing.
     *
     * The signature keys of each group's members go with them, see [backUpMemberKeys], whatever
     * came of the rest.
     */
    suspend fun backUp(): EmptyResult<GroupSyncError> = backUpMutex.withLock {
        val profiles = backUpProfiles()
        val memberKeys = backUpMemberKeys()

        if (profiles is Result.Error) profiles else memberKeys
    }

    private suspend fun backUpProfiles(): EmptyResult<GroupSyncError> {
        val pending = memberProfileDao.getNotBackedUp()

        // Most calls find nothing to write, and a write costs two requests at least.
        if (pending.isEmpty()) return Result.Success(Unit)

        val added = pending.associate { member ->
            member.userUuid to VaultMemberProfile(
                profileEncryptionKey = member.profileEncryptionKey,
                isFromOwner = member.isFromOwner,
                fullName = member.fullName,
            )
        }

        val write = write(added = added)

        if (write is Result.Error) return Result.Error(GroupSyncError.Vault(write.error))

        pending.forEach { member ->
            memberProfileDao.markBackedUp(
                userUuid = member.userUuid,
                profileEncryptionKey = member.profileEncryptionKey,
                isFromOwner = member.isFromOwner,
                fullName = member.fullName,
            )
        }

        return Result.Success(Unit)
    }

    /**
     * Carries to the vault the signature key of every current member of every group this device
     * holds, as its tree last held them, see [GroupDao.getHeldMemberSignatureKeys], and drops those
     * of the groups nobody is left to vouch for, see [GroupDao.getGroupsNobodyVouchesFor]: a device
     * coming back from a log-out takes a GroupInfo from any of them, a contact or not, see
     * [MemberProfileBook.memberKeysOf].
     *
     * A group this device holds no tree of is left as the vault holds it: one brought back after a
     * log-out, which needs what the vault holds to rejoin at all.
     *
     * Compared first with what the last backup found, by [digestOf], as most calls find nothing has
     * changed, and reading the vault costs two requests at least. Two different states with the same
     * digest would at worst keep the vault a change behind until the next one.
     */
    private suspend fun backUpMemberKeys(): EmptyResult<GroupSyncError> {
        val changed = heldMemberKeys()
            .groupBy { it.groupUuid }
            .mapValues { (_, keys) -> keys.associate { it.userUuid to it.signatureKey } }

        val forgotten = groupsToForget().toSet()

        if (changed.isEmpty() && forgotten.isEmpty()) return Result.Success(Unit)

        val digest = digestOf(changed = changed, forgotten = forgotten)

        if (digest == backedUpMemberKeys()) return Result.Success(Unit)

        val write = whileContended { memberProfileVault.updateGroupMemberKeys(changed = changed, forgotten = forgotten) }

        if (write is Result.Error) return Result.Error(GroupSyncError.Vault(write.error))

        markMemberKeysBackedUp(digest)

        return Result.Success(Unit)
    }

    /** Writes [added] into the vault, see [whileContended]. */
    private suspend fun write(added: Map<String, VaultMemberProfile>): EmptyResult<VaultError> =
        whileContended { memberProfileVault.updateMemberProfiles(added = added) }

    /**
     * Runs [write], reading and merging again while another device's write keeps getting in first,
     * at most [BACK_UP_ATTEMPTS] times.
     */
    private suspend fun whileContended(write: suspend () -> EmptyResult<VaultError>): EmptyResult<VaultError> {
        repeat(BACK_UP_ATTEMPTS - 1) {
            val written = write()

            if (written !is Result.Error || written.error != VaultError.Contended) return written
        }

        return write()
    }

    /**
     * Brings back the members the vault holds, which a log-out or a reinstall took off this device,
     * with the names read with their keys, see [putBack]. Does nothing once they are back, see
     * [authenticationUtils.setMemberProfilesToRestore].
     *
     * Before anything is taken from a group: the groups brought back after a log-out read nothing
     * until their creator adds the user back, and one joined since could relay a key the vault
     * holds better.
     */
    suspend fun restore(): EmptyResult<GroupSyncError> = restoreMutex.withLock {
        if (!isToRestore()) return@withLock Result.Success(Unit)

        val book = when (val read = memberProfileVault.getMemberProfileBook()) {
            is Result.Success -> read.data
            is Result.Error -> return@withLock Result.Error(GroupSyncError.Vault(read.error))
        }

        putBack(book = book)

        markRestored()

        Result.Success(Unit)
    }

    /**
     * Puts what [book] holds back on this device, for [restore]: each member with the key the vault
     * holds for them, and the name read with it, as the vault holds them already.
     *
     * With no time for a key from its owner, the book keeping none, so that any other key its
     * owner hands over in a group replaces it, see [MemberProfileEntity.ownerKeySentAt]: this runs
     * once after a log-in, onto a device holding no MLS state, and every message a group hands
     * one over in was sent after that, later than any the vault's keys came in.
     *
     * Their names are read again all the same, see [readNames]: the book does not say which key a
     * name was read with, and a name read with a key since replaced is the one the next read finds
     * newer. A member this device holds a row for already keeps it.
     */
    internal suspend fun putBack(book: MemberProfileBook) {
        memberProfileDao.insertNew(
            memberProfiles = book.members.map { (userUuid, member) ->
                MemberProfileEntity(
                    userUuid = userUuid,
                    profileEncryptionKey = member.profileEncryptionKey,
                    isFromOwner = member.isFromOwner,
                    ownerKeySentAt = null,
                    fullName = member.fullName,
                    nameKeyHash = null,
                    isBackedUp = true,
                )
            },
        )
    }
}

/** What tells one state of the groups' member keys from another, whatever order they were read in. */
private fun digestOf(changed: Map<String, Map<String, ByteArray>>, forgotten: Set<String>): Int {
    val held = changed.entries.sortedBy { it.key }.joinToString(separator = ";") { (groupUuid, members) ->
        val keys = members.entries.sortedBy { it.key }.joinToString(separator = ",") { (userUuid, key) ->
            "$userUuid=${Base64.Default.encode(key)}"
        }

        "$groupUuid:$keys"
    }

    return "$held|${forgotten.sorted().joinToString(separator = ",")}".hashCode()
}

/**
 * How many times [MemberProfileRepository.backUp] writes the members, reading and merging them
 * again each time another device wrote in between. Each round loses only to a device writing at the
 * same instant, so a handful is already far more than a real race needs.
 */
private const val BACK_UP_ATTEMPTS = 3
