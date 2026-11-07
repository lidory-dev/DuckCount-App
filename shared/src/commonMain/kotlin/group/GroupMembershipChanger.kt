package group

import app.lidory.duckcount.GroupLimits
import authenticationUtils.getAuthenticatedUserUuid
import contact.ContactSyncError
import database.data.local.dao.ContactDao
import database.data.local.dao.GroupDao
import database.data.local.dao.IdentityConflictDao
import database.data.local.entity.GroupEntity
import database.data.local.entity.PendingMembershipChangeEntity
import database.data.remote.EncryptedFieldDto
import database.data.repository.ContactRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import mls.ConsumeKeyPackageDto
import mls.GroupRoster
import mls.KeyPackageApi
import mls.MlsIdentityError
import mls.MlsIdentityRepository
import mls.addMembersTo
import mls.commitKeysIn
import mls.discardPendingCommitIn
import mls.encryptApplicationMessage
import mls.isRetryable
import mls.removeMembersFrom
import mls.rosterOf
import mls.updateIn
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import util.EmptyResult
import util.NetworkError
import util.Result
import util.onError
import vault.ProfileKeyVault
import vault.VaultError
import kotlin.io.encoding.Base64

/** What a change to a group's members came to, when it did not fail, see [GroupMembershipChanger]. */
enum class MembershipChangeOutcome {
    /**
     * The server took the change, or there was none to make: everybody named was a member
     * already, or for a removal no member any more.
     */
    MADE,

    /**
     * The server has not been heard to take the change yet, see [GroupMembershipAnswer.Failed] and
     * [GroupMembershipAnswer.RateLimited]: it is sent again until it is, and reaches the members
     * then.
     */
    PENDING,
}

/**
 * Adds members to a group the user created, or removes some, and sends the change to the server,
 * which hands the Commit to the group's members and the Welcome to those it adds. Only the creator
 * changes a group's members: the members' devices take Commits from nobody else, see
 * [GroupEntity.ownerUuid], but for any member's removal of those who departed from the group, see
 * [removeDepartedUnderGroupsLock], and the creator's replacement of a Commit nobody could apply, see
 * [replaceUnderGroupsLock], which the group sync makes.
 *
 * In this order:
 * 1. The group is checked, see [changeable]: the user created it, and so is still in it, a creator
 *    never being removed from their own group, and this device holds its MLS state, with no change
 *    to it waiting on the server, its creation included. The library builds one Commit at a time
 *    per group, and a Commit built over a change the server may hold would be refused by every
 *    member who got that change.
 * 2. For an addition, the new members are checked as a creation checks its invitees, see
 *    [inviteeRefusalOf], with at most [GroupLimits.MAX_MEMBERS] members once they are in, and a key
 *    package is asked for each of them, see [fetchKeyPackages]. Anybody named who is a member
 *    already, or for a removal who is no member, is left out: a change asked for twice is made
 *    once. The key ring the user's copy of the group's next key is sealed under is read first, see
 *    [GroupKeyCopies.sealer].
 * 3. The Commit is built with the library, an addition's Welcome handing the new members the key
 *    to every member's profile this device holds, see [profileKeysToHandOut], and with it the link
 *    back from the key of the epoch it leads to, which the library derives before anything is
 *    applied, to the key of the epoch it is built in, see [AddGroupMembersBody.previousKey], and
 *    the user's copy of the newer key, see [AddGroupMembersBody.keyCopy]. All of it is saved with
 *    the request that will make it, see [PendingMembershipChangeEntity], before anything is sent.
 *    The members already in the group are handed the same keys to the members' profiles in an
 *    application message of the epoch before the Commit's, see
 *    [AddGroupMembersBody.profileKeysMessage], which goes with it. An addition handing over none
 *    of the user's own leaves the group owed it, see [GroupEntity.handedProfileKeyHash].
 * 4. It is sent, see [GroupMembershipSender.send], its resend booked first, see
 *    [GroupMembershipSender.bookResend]. Only a refusal undoes it; the server not answering, or
 *    answering that it cannot take it yet, leaves it to go out again, see
 *    [MembershipChangeOutcome.PENDING]. Refused for its epoch, another member's Commit having got
 *    there first, it is built once more once the group sync read that Commit, see [add] and
 *    [remove], and by the group sync itself when it is refused so as it is sent again, see
 *    [remakeOutdatedUnderGroupsLock].
 *
 * The user removes members by hand, and the group sync removes those who departed, members who
 * left the group, on purpose, see [GroupLeaver], or as a device declining it does, and accounts
 * deleted since, through the server's route for them, see [removeDepartedUnderGroupsLock], which
 * any member may take, the creator included.
 *
 * Everything that can fail without leaving anything behind comes before the Commit is built. From
 * there to the answer is not cancellable: a Commit built and never saved would hold the group's
 * next change back, and a change sent and left there would wait for the next sync to reach its
 * members.
 *
 * A change can wait on a sync under way for as long as that sync takes, and the device can lock
 * meanwhile, which leaves nothing the vault holds readable: who is signed in, the user's MLS
 * identity until it is opened in this process, the contacts still to bring back, the key ring and
 * the key to the user's profile. Before the Commit is built, a change the user asked for is not
 * made then, see [GroupMembershipError.DeviceLocked], and an addition reads all of it before it
 * asks for any key package, which would be spent for nothing. The sync's own changes read the
 * vault as is instead, and its pass stops at the first read that throws, see
 * [removeDepartedUnderGroupsLock]. After the Commit is built, nothing reads the vault.
 *
 * [userUuid] is [getAuthenticatedUserUuid], [restoreContacts] is [ContactRepository.restore],
 * [contacts] and [blocked] are [ContactDao.getUserUuids] and [IdentityConflictDao.getBlocked],
 * [client] is [MlsIdentityRepository.client], [keyCopySealer] is [GroupKeyCopies.sealer],
 * [consumeKeyPackage] is [KeyPackageApi.consumeKeyPackage], [group] is [GroupDao.get],
 * [hasPendingChange] is [GroupDao.hasPendingChange], [savePending] is
 * [GroupDao.upsertMembershipChange], [outdatedChanges] are those of [GroupDao.getMembershipChanges]
 * to be made again, [dropOutdated] is [GroupDao.deleteOutdatedMembershipChange], [bookResend] and
 * [send] are [GroupMembershipSender.bookResend]
 * and [GroupMembershipSender.send], [ownProfileKey] is
 * [ProfileKeyVault.getOrCreateProfileEncryptionKey], null when it fails, [contactProfileKey] is the
 * key [ContactDao.get] holds, [learnedProfileKey] is [MemberProfileRepository.keyFor],
 * [oweProfileKey] is [GroupDao.setHandedProfileKeyHash] to null and [syncGroups] is [GroupSync.sync],
 * what it read told about through [GroupSyncAnnouncer], or what a test stands in for any of them.
 */
class GroupMembershipChanger internal constructor(
    private val userUuid: () -> String?,
    private val restoreContacts: suspend () -> EmptyResult<ContactSyncError>,
    private val contacts: suspend () -> List<String>,
    private val blocked: suspend () -> List<String>,
    private val client: suspend () -> Result<MlsClient, MlsIdentityError>,
    private val keyCopySealer: suspend () -> Result<KeyCopySealer, VaultError>,
    private val consumeKeyPackage: suspend (userUuid: String) -> Result<ConsumeKeyPackageDto, NetworkError>,
    private val group: suspend (groupUuid: String) -> GroupEntity?,
    private val hasPendingChange: suspend (groupUuid: String) -> Boolean,
    private val savePending: suspend (change: PendingMembershipChangeEntity) -> Unit,
    private val outdatedChanges: suspend () -> List<PendingMembershipChangeEntity>,
    private val dropOutdated: suspend (groupUuid: String) -> Unit,
    private val bookResend: suspend () -> Unit,
    private val send: suspend (
        change: PendingMembershipChange,
        group: GroupEntity,
        client: MlsClient,
    ) -> GroupMembershipAnswer,
    private val ownProfileKey: suspend () -> ByteArray?,
    private val contactProfileKey: suspend (userUuid: String) -> ByteArray?,
    private val learnedProfileKey: suspend (userUuid: String) -> ByteArray?,
    private val oweProfileKey: suspend (groupUuid: String) -> Unit,
    private val syncGroups: suspend () -> Unit,
) {
    /**
     * [groupSync] is lazy: the sync removes those who departed through this very changer, see
     * [removeDepartedUnderGroupsLock].
     */
    constructor(
        contactRepository: ContactRepository,
        contactDao: ContactDao,
        identityConflictDao: IdentityConflictDao,
        mlsIdentityRepository: MlsIdentityRepository,
        groupKeyCopies: GroupKeyCopies,
        keyPackageApi: KeyPackageApi,
        groupDao: GroupDao,
        groupMembershipSender: GroupMembershipSender,
        profileKeyVault: ProfileKeyVault,
        memberProfileRepository: MemberProfileRepository,
        groupSync: Lazy<GroupSync>,
        groupSyncAnnouncer: GroupSyncAnnouncer,
    ) : this(
        userUuid = ::getAuthenticatedUserUuid,
        restoreContacts = contactRepository::restore,
        contacts = contactDao::getUserUuids,
        blocked = identityConflictDao::getBlocked,
        client = mlsIdentityRepository::client,
        keyCopySealer = groupKeyCopies::sealer,
        consumeKeyPackage = keyPackageApi::consumeKeyPackage,
        group = groupDao::get,
        hasPendingChange = groupDao::hasPendingChange,
        savePending = groupDao::upsertMembershipChange,
        outdatedChanges = { groupDao.getMembershipChanges().filter { it.isOutdated } },
        dropOutdated = groupDao::deleteOutdatedMembershipChange,
        bookResend = groupMembershipSender::bookResend,
        send = groupMembershipSender::send,
        ownProfileKey = { (profileKeyVault.getOrCreateProfileEncryptionKey() as? Result.Success)?.data },
        contactProfileKey = { userUuid -> contactDao.get(userUuid = userUuid)?.profileEncryptionKey },
        learnedProfileKey = memberProfileRepository::keyFor,
        oweProfileKey = { groupUuid ->
            groupDao.setHandedProfileKeyHash(uuid = groupUuid, handedProfileKeyHash = null)
        },
        // As for an expense, see GroupExpenseWriter: what it reads of the other groups is read
        // once, so it is told about here, or never.
        syncGroups = { groupSyncAnnouncer.announce(pass = groupSync.value.sync(contactsTakenIn = false)) },
    )

    /**
     * Adds [userUuids], at least one of them, to the group [groupUuid], see [GroupMembershipChanger].
     */
    suspend fun add(groupUuid: String, userUuids: Set<String>): Result<MembershipChangeOutcome, GroupMembershipError> {
        require(userUuids.isNotEmpty()) { "Members are added one at least." }

        return onceCaughtUp {
            withGroupsLock {
                // Once the lock is held: the device may have locked as this waited for it, see above.
                val selfUuid = when (val read = unlockedOrError { userUuid() }) {
                    is Result.Success -> read.data ?: return@withGroupsLock Result.Error(GroupMembershipError.NoSession)
                    is Result.Error -> return@withGroupsLock read
                }

                addLocked(selfUuid = selfUuid, groupUuid = groupUuid, userUuids = userUuids)
            }
        }
    }

    /**
     * Removes [userUuids], at least one of them and never the user, from the group [groupUuid],
     * see [GroupMembershipChanger].
     */
    suspend fun remove(
        groupUuid: String,
        userUuids: Set<String>,
    ): Result<MembershipChangeOutcome, GroupMembershipError> {
        require(userUuids.isNotEmpty()) { "Members are removed one at least." }

        return onceCaughtUp {
            withGroupsLock {
                // Once the lock is held: the device may have locked as this waited for it, see above.
                val selfUuid = when (val read = unlockedOrError { userUuid() }) {
                    is Result.Success -> read.data ?: return@withGroupsLock Result.Error(GroupMembershipError.NoSession)
                    is Result.Error -> return@withGroupsLock read
                }

                val held = when (val checked = changeable(groupUuid = groupUuid, selfUuid = selfUuid)) {
                    is Result.Success -> checked.data
                    is Result.Error -> return@withGroupsLock checked
                }

                removeLocked(
                    selfUuid = selfUuid,
                    held = held,
                    userUuids = userUuids,
                    vault = VaultReads.WHILE_UNLOCKED,
                    changeOf = ::removalOf,
                )
            }
        }
    }

    /**
     * Makes again the change the user asked for to the group [groupUuid] whose Commit the server
     * refused for its epoch as it was sent again, see [PendingMembershipChangeEntity.isOutdated],
     * for a caller already holding [withGroupsLock], which this does not take: the group sync, once
     * it read the Commit that got there first, see [GroupSync]. Built anew over where the group
     * stands, as [add] and [remove] build it, for those it names still to add or to remove, and
     * sent: said as the first send of the change was, see [MembershipChangeOutcome.PENDING], and
     * as the screen showed it since, as waiting, see [GroupDao.hasPendingChange]. Gives what came of
     * it, or null when no such change is pending for the group.
     *
     * The change built anew is saved beside it, see [PendingMembershipChangeEntity], and drops it
     * once sent, as nothing left to make, everybody named in or out already, does. Refused
     * for its epoch again, the one built anew goes, see [GroupMembershipSender], and this is kept to
     * be made by the next sync, as is one kept from being built by what says nothing about it, see
     * [isMadeLater]; any other refusal drops it, as one of the server's does any change. The vault
     * is read as for a change the user asks for, a locked device being one of those.
     */
    internal suspend fun remakeOutdatedUnderGroupsLock(
        groupUuid: String,
    ): Result<MembershipChangeOutcome?, GroupMembershipError> {
        val outdated = outdatedChanges().firstOrNull { it.groupUuid == groupUuid } ?: return Result.Success(null)

        val selfUuid = userUuid() ?: return Result.Error(GroupMembershipError.NoSession)

        val remade = when (val change = outdated.toChange()) {
            is PendingMembershipChange.Addition -> addLocked(
                selfUuid = selfUuid,
                groupUuid = groupUuid,
                userUuids = change.body.newMemberUuids.toSet(),
                isRemaking = true,
            )

            is PendingMembershipChange.Removal ->
                when (val checked = changeable(groupUuid = groupUuid, selfUuid = selfUuid, isCaughtUp = true)) {
                    is Result.Success -> removeLocked(
                        selfUuid = selfUuid,
                        held = checked.data,
                        userUuids = change.body.removedMemberUuids.toSet(),
                        vault = VaultReads.WHILE_UNLOCKED,
                        changeOf = ::removalOf,
                    )

                    is Result.Error -> checked
                }

            is PendingMembershipChange.DepartedRemoval,
            is PendingMembershipChange.Replacement,
            is PendingMembershipChange.Rejoin,
            -> error("Only a change the user asked for is made again.")
        }

        when (remade) {
            // Nothing left to make: anything made is pending beside it, or taken.
            is Result.Success -> dropOutdated(groupUuid)

            is Result.Error -> when {
                // Kept: its Commit refused for its epoch, the one made anew went, see
                // GroupMembershipSender, or nothing was made in its place.
                remade.error == GroupMembershipError.Outdated || remade.error.isMadeLater() -> Unit

                else -> dropOutdated(groupUuid)
            }
        }

        return remade
    }

    /** The removal of [removedUuids] from the group [groupUuid] by [commit], with its [keys], see [remove]. */
    private fun removalOf(groupUuid: String, commit: ByteArray, removedUuids: List<String>, keys: LinkedKeys) =
        PendingMembershipChange.Removal(
            groupUuid = groupUuid,
            body = RemoveGroupMembersBody(
                nonce = newChangeNonce(),
                commitMessage = Base64.Default.encode(commit),
                removedMemberUuids = removedUuids,
                previousKey = keys.previousKey,
                keyCopy = keys.keyCopy,
            ),
            commitEpoch = keys.epoch,
        )

    /**
     * Removes [userUuids], at least one of them and never the user, members who departed from the
     * group [groupUuid], see [GroupDto.departed], through the server's route for them, see
     * [RemoveDepartedMembersBody], for a caller already holding [withGroupsLock], which this does
     * not take: the group sync, see [GroupSync]. Any member may, not only its creator, whose
     * removals any other member's device takes whatever they remove: the members' devices take this
     * one from anybody, so long as it removes exactly those the server checked had departed, see
     * [GroupMessageDto.departedMemberUuids]. To be called under that lock alone, which is not
     * reentrant: [remove] would wait on it for good there.
     *
     * The vault is read as is: on a locked device the read throws, and the sync stops its pass there,
     * telling its caller to try again once the device is unlocked, see [GroupSyncError.DeviceLocked].
     * Refused for its epoch, see [GroupMembershipError.Outdated], it is the sync's to decide again
     * once it read the Commit that got there first.
     */
    internal suspend fun removeDepartedUnderGroupsLock(
        groupUuid: String,
        userUuids: Set<String>,
    ): Result<MembershipChangeOutcome, GroupMembershipError> {
        require(userUuids.isNotEmpty()) { "Members are removed one at least." }

        val selfUuid = userUuid() ?: return Result.Error(GroupMembershipError.NoSession)

        val held = when (val checked = heldOf(groupUuid = groupUuid)) {
            is Result.Success -> checked.data
            is Result.Error -> return checked
        }

        return removeLocked(
            selfUuid = selfUuid,
            held = held,
            userUuids = userUuids,
            vault = VaultReads.AS_IS,
        ) { _, commit, removedUuids, keys ->
            PendingMembershipChange.DepartedRemoval(
                groupUuid = groupUuid,
                body = RemoveDepartedMembersBody(
                    nonce = newChangeNonce(),
                    commitMessage = Base64.Default.encode(commit),
                    removedMemberUuids = removedUuids,
                    previousKey = keys.previousKey,
                    keyCopy = keys.keyCopy,
                ),
                commitEpoch = keys.epoch,
            )
        }
    }

    /**
     * Replaces the Commit every member's device refused in the group [groupUuid], a group the user
     * created, with one of the user's for the epoch this device is at, removing those of
     * [userUuids] still in its tree, never the user, see [ReplaceGroupCommitBody]: or nobody, a
     * Commit that changes none of its members, see [updateIn]. For a caller already holding
     * [withGroupsLock], which this does not take: the group sync, which finds this device behind the
     * server with nothing left to read, see [GroupSync]. The vault is read as is, as for
     * [removeDepartedUnderGroupsLock].
     *
     * Made ahead of any change the user asked for that is to be made again, see
     * [PendingMembershipChangeEntity.isOutdated], kept beside it: the server takes no Commit of the
     * epoch this device is at but this one, which every member's device stays in until it comes,
     * and the sync makes that change once the server took it, see [remakeOutdatedUnderGroupsLock].
     */
    internal suspend fun replaceUnderGroupsLock(
        groupUuid: String,
        userUuids: Set<String>,
    ): Result<MembershipChangeOutcome, GroupMembershipError> {
        val selfUuid = userUuid() ?: return Result.Error(GroupMembershipError.NoSession)

        val held = when (val checked = changeable(groupUuid = groupUuid, selfUuid = selfUuid, isCaughtUp = true)) {
            is Result.Success -> checked.data
            is Result.Error -> return checked
        }

        val client = when (val opened = clientAsIs()) {
            is Result.Success -> opened.data
            is Result.Error -> return opened
        }

        val roster = when (val read = settledRosterOf(client = client, mlsGroupId = held.mlsGroupId)) {
            is Result.Success -> read.data
            is Result.Error -> return read
        }

        val removedUuids = userUuids.filter { it in roster.memberUuids && it != selfUuid }

        val sealer = when (val read = sealerOf(vault = VaultReads.AS_IS)) {
            is Result.Success -> read.data
            is Result.Error -> return read
        }

        return withContext(NonCancellable) {
            val commit = try {
                if (removedUuids.isEmpty()) {
                    client.updateIn(mlsGroupId = held.mlsGroupId)
                } else {
                    client.removeMembersFrom(mlsGroupId = held.mlsGroupId, userUuids = removedUuids)
                }
            } catch (exception: MlsException) {
                return@withContext Result.Error(exception.toMembershipError())
            }

            val keys = when (val read = linkedKeysOf(client = client, held = held, sealer = sealer)) {
                is Result.Success -> read.data
                is Result.Error -> return@withContext read
            }

            val change = PendingMembershipChange.Replacement(
                groupUuid = groupUuid,
                body = ReplaceGroupCommitBody(
                    nonce = newChangeNonce(),
                    replacesEpoch = keys.epoch,
                    commitMessage = Base64.Default.encode(commit),
                    removedMemberUuids = removedUuids,
                    previousKey = keys.previousKey,
                    keyCopy = keys.keyCopy,
                ),
                commitEpoch = keys.epoch,
            )

            saveAndSend(change = change, held = held, client = client)
        }
    }

    /**
     * Adds [userUuids] to the group [groupUuid] as the user [selfUuid], see [add], or makes that
     * addition again when [isRemaking], see [remakeOutdatedUnderGroupsLock].
     */
    private suspend fun addLocked(
        selfUuid: String,
        groupUuid: String,
        userUuids: Set<String>,
        isRemaking: Boolean = false,
    ): Result<MembershipChangeOutcome, GroupMembershipError> {
        val held = when (val checked = changeable(groupUuid = groupUuid, selfUuid = selfUuid, isCaughtUp = isRemaking)) {
            is Result.Success -> checked.data
            is Result.Error -> return checked
        }

        // Before the pins are read, as for a creation: until the contacts are back, a contact
        // looks like a stranger, and their pin is not there.
        when (val restored = unlockedOrError { restoreContacts() }) {
            is Result.Success -> restored.data.onError { return Result.Error(GroupMembershipError.Contacts(it)) }
            is Result.Error -> return restored
        }

        val client = when (val opened = clientWhileUnlocked()) {
            is Result.Success -> opened.data
            is Result.Error -> return opened
        }

        val roster = when (val read = settledRosterOf(client = client, mlsGroupId = held.mlsGroupId)) {
            is Result.Success -> read.data
            is Result.Error -> return read
        }

        val newMemberUuids = userUuids - roster.memberUuids.toSet()

        if (newMemberUuids.isEmpty()) return Result.Success(MembershipChangeOutcome.MADE)

        // Before anything spends a key package.
        if (roster.memberUuids.size + newMemberUuids.size > GroupLimits.MAX_MEMBERS) {
            return Result.Error(GroupMembershipError.TooManyMembers)
        }

        refusalOf(client = client, userUuids = newMemberUuids)?.let { return Result.Error(it) }

        // Before any key package is asked for, which a device locked by now, or a key ring not
        // answering, would spend for nothing, see GroupMembershipChanger.
        val ownKey = when (val read = unlockedOrError { ownProfileKey() }) {
            is Result.Success -> read.data
            is Result.Error -> return read
        }

        val sealer = when (val read = sealerOf(vault = VaultReads.WHILE_UNLOCKED)) {
            is Result.Success -> read.data
            is Result.Error -> return read
        }

        val keyPackages = when (
            val fetched = fetchKeyPackages(inviteeUuids = newMemberUuids, consumeKeyPackage = consumeKeyPackage)
        ) {
            is KeyPackageFetch.Fetched -> fetched.keyPackages

            is KeyPackageFetch.Unreachable ->
                return Result.Error(GroupMembershipError.Unreachable(userUuids = fetched.userUuids))

            is KeyPackageFetch.Failed -> return Result.Error(GroupMembershipError.Network(fetched.error))
        }

        // Every member's once the new ones are in, as for a creation: the user's own, unless the
        // vault does not answer, which leaves the group owed it, see below, and the others' as
        // they were handed to the user, see profileKeysToHandOut.
        val profileKeys = profileKeysToHandOut(
            memberUuids = roster.memberUuids + keyPackages.map { it.userUuid },
            selfUuid = selfUuid,
            ownProfileKey = ownKey,
            contactProfileKey = contactProfileKey,
            learnedProfileKey = learnedProfileKey,
        )

        // With no entry of the user's own, the members go on holding whatever key of theirs they
        // held, an older one or none: the group is owed theirs, posted by the sync once the
        // addition is settled, see GroupProfileKeyDelivery. Before anything is built, so that no
        // addition goes out with the group still recorded as holding it; one that never goes out
        // leaves a post owed for nothing, read and changing nothing.
        if (selfUuid !in profileKeys) oweProfileKey(groupUuid)

        // For the members already in, who cannot read the Welcome: in the epoch the Commit leaves,
        // which they read it in whether the server lists it before the Commit or after, see
        // GroupSync.takeProfileKeys. Built before the Commit, once no Commit of the user's is
        // pending, see settledRosterOf. One that never goes out, the Commit failing or refused,
        // leaves nothing behind but a key of the user's spent in the group.
        val profileKeysMessage = try {
            client.encryptApplicationMessage(
                mlsGroupId = held.mlsGroupId,
                payload = profileKeysMessageOf(profileKeys = profileKeys),
            )
        } catch (exception: MlsException) {
            return Result.Error(
                if (exception.isRetryable()) GroupMembershipError.Storage else GroupMembershipError.Crypto,
            )
        }

        return withContext(NonCancellable) {
            val addition = try {
                client.addMembersTo(
                    mlsGroupId = held.mlsGroupId,
                    invitees = keyPackages,
                    welcomePayload = welcomePayloadOf(profileKeys = profileKeys),
                )
            } catch (exception: MlsException) {
                return@withContext Result.Error(
                    when {
                        exception is MlsException.IdentityKeyMismatch ->
                            GroupMembershipError.IdentityChanged(userUuids = setOf(exception.v1))

                        exception is MlsException.UnusableKeyPackages ->
                            GroupMembershipError.Unreachable(userUuids = exception.v1.toSet())

                        else -> exception.toMembershipError()
                    },
                )
            }

            val keys = when (val read = linkedKeysOf(client = client, held = held, sealer = sealer)) {
                is Result.Success -> read.data
                is Result.Error -> return@withContext read
            }

            val change = PendingMembershipChange.Addition(
                groupUuid = groupUuid,
                body = AddGroupMembersBody(
                    nonce = newChangeNonce(),
                    commitMessage = Base64.Default.encode(addition.commit),
                    profileKeysMessage = Base64.Default.encode(profileKeysMessage),
                    welcomeMessage = Base64.Default.encode(addition.welcome),
                    newMemberUuids = keyPackages.map { it.userUuid },
                    previousKey = keys.previousKey,
                    keyCopy = keys.keyCopy,
                ),
                commitEpoch = keys.epoch,
            )

            saveAndSend(change = change, held = held, client = client)
        }
    }

    /**
     * Removes [userUuids] from [held] as the user [selfUuid], reading the vault as [vault] says, the
     * change [changeOf] makes of the Commit, those it removes and its keys, see [LinkedKeys].
     */
    private suspend fun removeLocked(
        selfUuid: String,
        held: HeldGroup,
        userUuids: Set<String>,
        vault: VaultReads,
        changeOf: (
            groupUuid: String,
            commit: ByteArray,
            removedUuids: List<String>,
            keys: LinkedKeys,
        ) -> PendingMembershipChange,
    ): Result<MembershipChangeOutcome, GroupMembershipError> {
        // A member's way out is leaving, see GroupLeaver, never a removal of their own, and the
        // creator never leaves: nobody else could add anybody to the group.
        require(selfUuid !in userUuids) { "Nobody removes themselves from a group." }

        val client = when (val opened = clientOf(vault = vault)) {
            is Result.Success -> opened.data
            is Result.Error -> return opened
        }

        val roster = when (val read = settledRosterOf(client = client, mlsGroupId = held.mlsGroupId)) {
            is Result.Success -> read.data
            is Result.Error -> return read
        }

        val removedUuids = userUuids.filter { it in roster.memberUuids }

        if (removedUuids.isEmpty()) return Result.Success(MembershipChangeOutcome.MADE)

        val sealer = when (val read = sealerOf(vault = vault)) {
            is Result.Success -> read.data
            is Result.Error -> return read
        }

        return withContext(NonCancellable) {
            val commit = try {
                client.removeMembersFrom(mlsGroupId = held.mlsGroupId, userUuids = removedUuids)
            } catch (exception: MlsException) {
                return@withContext Result.Error(exception.toMembershipError())
            }

            val keys = when (val read = linkedKeysOf(client = client, held = held, sealer = sealer)) {
                is Result.Success -> read.data
                is Result.Error -> return@withContext read
            }

            saveAndSend(change = changeOf(held.group.uuid, commit, removedUuids, keys), held = held, client = client)
        }
    }

    /**
     * What the request storing the Commit just built in [held] carries for the epoch it leads to,
     * see [LinkedKeys]: the link back from that epoch's key, which the library derives before the
     * Commit is applied, see [commitKeysIn], to the key of the epoch it was built in, and the user's
     * copy of the newer key, sealed with [sealer]. The Commit is discarded again when either cannot
     * be made, nothing ever sending it.
     */
    private suspend fun linkedKeysOf(
        client: MlsClient,
        held: HeldGroup,
        sealer: KeyCopySealer,
    ): Result<LinkedKeys, GroupMembershipError> {
        val keys = try {
            client.commitKeysIn(mlsGroupId = held.mlsGroupId, groupUuid = held.group.uuid)
        } catch (exception: MlsException) {
            discardQuietly(client = client, held = held)

            return Result.Error(exception.toMembershipError())
        }

        val generation = keys.epoch + 1

        val previousKey = sealKeyLink(
            previousKey = keys.current,
            groupKey = keys.next,
            groupUuid = held.group.uuid,
            generation = generation,
        )

        val keyCopy = sealer.seal(groupUuid = held.group.uuid, groupKey = keys.next, keyGeneration = generation)

        if (previousKey == null || keyCopy == null) {
            discardQuietly(client = client, held = held)

            return Result.Error(GroupMembershipError.Crypto)
        }

        return Result.Success(LinkedKeys(epoch = keys.epoch, previousKey = previousKey, keyCopy = keyCopy))
    }

    /**
     * Runs [change], a change the user asked for, and once more should the server refuse it for its
     * epoch, see [GroupMembershipError.Outdated], once the groups are synced: another member's
     * Commit got there first, a removal of those who departed, which this device reads then, and
     * the change is built again over it, if it still has anything to make. Refused again, the
     * refusal is returned.
     */
    private suspend fun onceCaughtUp(
        change: suspend () -> Result<MembershipChangeOutcome, GroupMembershipError>,
    ): Result<MembershipChangeOutcome, GroupMembershipError> {
        val made = change()

        if ((made as? Result.Error)?.error != GroupMembershipError.Outdated) return made

        syncGroups()

        return change()
    }

    /** The user's MLS client, or why it does not open, reading the vault as [vault] says. */
    private suspend fun clientOf(vault: VaultReads): Result<MlsClient, GroupMembershipError> =
        when (vault) {
            VaultReads.AS_IS -> clientAsIs()
            VaultReads.WHILE_UNLOCKED -> clientWhileUnlocked()
        }

    /**
     * What seals the user's copy of the key a Commit leads to, see [GroupKeyCopies.sealer], or why
     * it cannot be read, reading the vault as [vault] says.
     */
    private suspend fun sealerOf(vault: VaultReads): Result<KeyCopySealer, GroupMembershipError> {
        val read = when (vault) {
            VaultReads.AS_IS -> keyCopySealer()

            VaultReads.WHILE_UNLOCKED -> when (val unlocked = unlockedOrError { keyCopySealer() }) {
                is Result.Success -> unlocked.data
                is Result.Error -> return unlocked
            }
        }

        return when (read) {
            is Result.Success -> read
            is Result.Error -> Result.Error(GroupMembershipError.Vault(read.error))
        }
    }

    /** The user's MLS client, or why it does not open, the vault read as is, see [removeDepartedUnderGroupsLock]. */
    private suspend fun clientAsIs(): Result<MlsClient, GroupMembershipError> =
        when (val opened = client()) {
            is Result.Success -> opened
            is Result.Error -> Result.Error(GroupMembershipError.Identity(opened.error))
        }

    /**
     * The user's MLS client, for a change they asked for, or why it does not open: the device having
     * locked before this process first opened it included, see [GroupMembershipError.DeviceLocked].
     */
    private suspend fun clientWhileUnlocked(): Result<MlsClient, GroupMembershipError> =
        when (val read = unlockedOrError { clientAsIs() }) {
            is Result.Success -> read.data
            is Result.Error -> read
        }

    /**
     * The group [groupUuid] with its MLS group, when the user [selfUuid] may change its members
     * from this device, see [GroupMembershipChanger], or why not, the change pending for it aside
     * when [isCaughtUp], see [heldOf].
     */
    private suspend fun changeable(
        groupUuid: String,
        selfUuid: String,
        isCaughtUp: Boolean = false,
    ): Result<HeldGroup, GroupMembershipError> {
        val group = group(groupUuid) ?: return Result.Error(GroupMembershipError.NotHeld)

        if (group.ownerUuid != selfUuid) return Result.Error(GroupMembershipError.NotCreator)

        return heldOf(groupUuid = groupUuid, isCaughtUp = isCaughtUp)
    }

    /**
     * The group [groupUuid] with its MLS group, when this device holds that state, the user still in
     * the group, with no change to it pending, see [GroupMembershipChanger], or why not: whoever
     * created it. The change pending aside when [isCaughtUp]: one the group sync makes once it read
     * the group, which it reads with no change waiting on the server there, see [GroupSync], over
     * the change to be made again, see [PendingMembershipChangeEntity.isOutdated], which it makes,
     * see [remakeOutdatedUnderGroupsLock], or goes ahead of, see [replaceUnderGroupsLock].
     */
    private suspend fun heldOf(groupUuid: String, isCaughtUp: Boolean = false): Result<HeldGroup, GroupMembershipError> {
        val group = group(groupUuid)?.takeIf { !it.isLeft } ?: return Result.Error(GroupMembershipError.NotHeld)

        val mlsGroupId = group.mlsGroupId ?: return Result.Error(GroupMembershipError.NotHeld)

        if (!isCaughtUp && hasPendingChange(groupUuid)) return Result.Error(GroupMembershipError.ChangePending)

        return Result.Success(HeldGroup(group = group, mlsGroupId = mlsGroupId))
    }

    /** Discards the Commit just built in [held], should the change never be saved, see [settledRosterOf]. */
    private suspend fun discardQuietly(client: MlsClient, held: HeldGroup) {
        try {
            client.discardPendingCommitIn(mlsGroupId = held.mlsGroupId)
        } catch (_: MlsException) {
            // The next change to the group discards it, see settledRosterOf.
        }
    }

    /**
     * Who is in the group [mlsGroupId], once a Commit of the user's left pending there is discarded:
     * with no change saved for the group, see [changeable], it is one the process did not live to
     * save, so never sent, see [saveAndSend], and it would keep the group from building another.
     */
    private suspend fun settledRosterOf(
        client: MlsClient,
        mlsGroupId: ByteArray,
    ): Result<GroupRoster, GroupMembershipError> =
        try {
            val roster = client.rosterOf(mlsGroupId = mlsGroupId)

            if (roster.hasPendingCommit) client.discardPendingCommitIn(mlsGroupId = mlsGroupId)

            Result.Success(roster)
        } catch (exception: MlsException) {
            Result.Error(exception.toMembershipError())
        }

    /**
     * Why [userUuids] cannot all be added, or null when they can, see [inviteeRefusalOf], as for a
     * creation.
     */
    private suspend fun refusalOf(client: MlsClient, userUuids: Set<String>): GroupMembershipError? =
        when (
            val refusal = client.inviteeRefusalOf(inviteeUuids = userUuids, contacts = contacts, blocked = blocked)
        ) {
            is InviteeRefusal.NotContacts -> GroupMembershipError.NotContacts(userUuids = refusal.userUuids)
            is InviteeRefusal.IdentityChanged -> GroupMembershipError.IdentityChanged(userUuids = refusal.userUuids)
            InviteeRefusal.Storage -> GroupMembershipError.Storage
            null -> null
        }

    /**
     * Saves [change], whose Commit the library just built in the group [held], books its resend
     * and sends it, see [GroupMembershipSender.send].
     *
     * A change that could not be saved has its Commit discarded again, as nothing would ever send
     * it, or by the next change to the group if that fails too, see [settledRosterOf].
     */
    private suspend fun saveAndSend(
        change: PendingMembershipChange,
        held: HeldGroup,
        client: MlsClient,
    ): Result<MembershipChangeOutcome, GroupMembershipError> {
        try {
            savePending(change.toEntity())
        } catch (_: Exception) {
            discardQuietly(client = client, held = held)

            return Result.Error(GroupMembershipError.Storage)
        }

        // Before the send, as for a creation: a process not living through it leaves the change
        // to go out again, and the group's next change held back until it does.
        bookResend()

        return when (val answer = send(change, held.group, client)) {
            GroupMembershipAnswer.Made,
            GroupMembershipAnswer.AlreadyMade,
            -> Result.Success(MembershipChangeOutcome.MADE)

            is GroupMembershipAnswer.DeletedMembers ->
                Result.Error(GroupMembershipError.Deleted(userUuids = answer.userUuids.toSet()))

            is GroupMembershipAnswer.Refused -> Result.Error(GroupMembershipError.Network(answer.error))

            is GroupMembershipAnswer.KeyGenerationNotCurrent -> Result.Error(GroupMembershipError.Outdated)

            is GroupMembershipAnswer.RateLimited,
            is GroupMembershipAnswer.Failed,
            -> Result.Success(MembershipChangeOutcome.PENDING)
        }
    }
}

/** A group the user may change the members of, and its MLS group, see [GroupMembershipChanger.changeable]. */
private class HeldGroup(val group: GroupEntity, val mlsGroupId: ByteArray)

/**
 * What the request storing a Commit built in [epoch] carries for the epoch it leads to, see
 * [GroupMembershipChanger.linkedKeysOf]: [previousKey], the link back to the key of [epoch], and
 * [keyCopy], the user's copy of the newer key.
 */
private class LinkedKeys(val epoch: Long, val previousKey: EncryptedFieldDto, val keyCopy: EncryptedFieldDto)

/** How a change reads the vault, see [GroupMembershipChanger]. */
private enum class VaultReads {
    /** As is, which throws once the device locks: the sync's own changes, its pass stopping there. */
    AS_IS,

    /** Unless the device is locked, which is a failure of its own: a change the user asked for. */
    WHILE_UNLOCKED,
}

/**
 * Whether a change kept from being made by this is to be made again later, see
 * [GroupMembershipChanger.remakeOutdatedUnderGroupsLock]: what says nothing about the change, the
 * device locked, the user's storage, identity, vault or contacts failing, and the server not
 * answering, see [membershipAnswerOf], rather than refusing it.
 */
private fun GroupMembershipError.isMadeLater(): Boolean =
    when (this) {
        GroupMembershipError.DeviceLocked,
        GroupMembershipError.Storage,
        is GroupMembershipError.Identity,
        is GroupMembershipError.Vault,
        is GroupMembershipError.Contacts,
        -> true

        is GroupMembershipError.Network -> membershipAnswerOf(error) is GroupMembershipAnswer.Failed

        else -> false
    }

/** What the library refusing a change with this comes to. */
private fun MlsException.toMembershipError(): GroupMembershipError =
    if (isRetryable()) GroupMembershipError.Storage else GroupMembershipError.Crypto

/**
 * What [read], a read of the vault for a change the user asked for, gives, or
 * [GroupMembershipError.DeviceLocked] when the device is locked: what the vault holds is read with
 * keys a locked device cannot use, and reading it throws an [IllegalStateException] then.
 */
private inline fun <T> unlockedOrError(read: () -> T): Result<T, GroupMembershipError> =
    try {
        Result.Success(read())
    } catch (exception: CancellationException) {
        // The caller going away, which the catch below would otherwise take for a locked device,
        // a CancellationException being an IllegalStateException.
        throw exception
    } catch (_: IllegalStateException) {
        Result.Error(GroupMembershipError.DeviceLocked)
    }
