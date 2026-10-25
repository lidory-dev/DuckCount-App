package group

import authenticationUtils.getAuthenticatedUserUuid
import contact.ContactSyncError
import contact.ContactSyncPass
import database.data.local.dao.ContactDao
import database.data.local.dao.GroupDao
import database.data.local.dao.UserDao
import database.data.local.entity.DeclinedGroupEntity
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupMemberEntity
import database.data.repository.ContactRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import mls.MlsIdentityError
import mls.MlsIdentityRepository
import mls.deleteGroupQuietly
import mls.inspectGroupWelcome
import mls.isRetryable
import mls.joinGroupOf
import mls.pinnedSignatureKeyFor
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import uniffi.lib_duckcount.WelcomeInspection
import util.EmptyResult
import util.NetworkError
import util.Result
import util.onError
import vault.VaultError
import vault.VaultRepository
import kotlin.io.encoding.Base64

/** A group the user was added to and joined in a [GroupSync] pass, for them to be told about. */
data class GroupArrival(
    val groupUuid: String,
    val title: String,

    /** Who added the user: whoever wrote the Welcome, one of the user's contacts. */
    val adderUuid: String,

    /** Null while this device has yet to read their name. */
    val adderFullName: String?,
)

/** What one [GroupSync.sync] came to. */
class GroupSyncPass(
    /** The groups the user was added to and joined, whatever else the pass came to. */
    val joined: List<GroupArrival>,

    /** The first thing that kept the pass from doing all it had to, or null when nothing did. */
    val failure: GroupSyncError?,
)

/**
 * Brings the user's groups on this device in line with what the server holds, on both sides of a
 * group's creation: the creator's, whose creation may not have reached the server yet, and the
 * invitees', who join it from its Welcome.
 *
 * In this order, under [withGroupsLock] as a creation is, since both read and write the same:
 * 1. Every creation the server has not been heard to take is sent again, see
 *    [GroupCreationSender.resendPending].
 * 2. Every group key the vault does not hold yet is backed up, see [GroupKeyBackUp.backUpPending].
 * 3. Every group the server lists that this device neither holds nor declined is taken in, see
 *    [takeIn]: brought back from the key the vault kept of it, after a log-out took it off this
 *    device, or else joined from the Welcome waiting there, or declined. One with neither is
 *    declined too, nothing being left that could ever open it here.
 *
 * A group is only ever joined from somebody who may add the user to one, see [mayAdd]: the server
 * lets anybody create a group with anybody in it, and keeps them a member whatever they make of the
 * Welcome. So a group whose Welcome anybody else wrote is declined, for good, see
 * [DeclinedGroupEntity], its Welcome acknowledged rather than left to hold a key package back from
 * pruning, see [hasNoWelcomeWaiting]. Who wrote it is the Welcome's word, not the server's, read
 * before anything is stored, and so is who is in the group, see [join].
 *
 * Every step is attempted even when one fails, and the first failure is reported, with the groups
 * joined either way, see [GroupSyncPass]: what was joined is told about once, by the pass that
 * joined it. Whatever did not get done is picked up by the next sync.
 *
 * [userUuid] is [getAuthenticatedUserUuid], [resendPending] is [GroupCreationSender.resendPending],
 * [backUpPending] and [backUp] are those of [GroupKeyBackUp], [restoreContacts] is
 * [ContactRepository.restore], [listGroups], [listMessages] and [acknowledge] are
 * [GroupApi.getGroups], [GroupApi.getGroupMessages] and [GroupApi.acknowledgeGroupMessages],
 * [heldGroups], [declinedGroups], [save] and [recordDeclined] are [GroupDao.getUuids],
 * [GroupDao.getDeclinedUuids], [GroupDao.insertWithMembers] and [GroupDao.insertDeclined],
 * [vaultKeys] is [VaultRepository.getGroupKeys], [isUnblockedContact] is [ContactDao.isUnblocked],
 * [client] is [MlsIdentityRepository.client] and [fullName] is [UserDao.getFullName], or what a
 * test stands in for any of them.
 */
class GroupSync internal constructor(
    private val userUuid: () -> String?,
    private val resendPending: suspend () -> EmptyResult<NetworkError>,
    private val backUpPending: suspend () -> EmptyResult<VaultError>,
    private val restoreContacts: suspend () -> EmptyResult<ContactSyncError>,
    private val listGroups: suspend () -> Result<List<GroupDto>, NetworkError>,
    private val heldGroups: suspend () -> List<String>,
    private val declinedGroups: suspend () -> List<String>,
    private val vaultKeys: suspend () -> Result<Map<String, ByteArray>, VaultError>,
    private val listMessages: suspend (groupUuid: String) -> Result<ListGroupMessagesResponse, NetworkError>,
    private val acknowledge: suspend (groupUuid: String, messageUuids: List<String>) -> EmptyResult<NetworkError>,
    private val isUnblockedContact: suspend (userUuid: String) -> Boolean,
    private val client: suspend () -> Result<MlsClient, MlsIdentityError>,
    private val save: suspend (group: GroupEntity, members: List<GroupMemberEntity>) -> Unit,
    private val recordDeclined: suspend (groupUuid: String) -> Unit,
    private val backUp: suspend (groupUuid: String, groupKey: ByteArray) -> EmptyResult<VaultError>,
    private val fullName: suspend (userUuid: String) -> String?,
) {
    constructor(
        groupApi: GroupApi,
        groupDao: GroupDao,
        groupCreationSender: GroupCreationSender,
        groupKeyBackUp: GroupKeyBackUp,
        vaultRepository: VaultRepository,
        contactRepository: ContactRepository,
        contactDao: ContactDao,
        mlsIdentityRepository: MlsIdentityRepository,
        userDao: UserDao,
    ) : this(
        userUuid = ::getAuthenticatedUserUuid,
        resendPending = groupCreationSender::resendPending,
        backUpPending = groupKeyBackUp::backUpPending,
        restoreContacts = contactRepository::restore,
        listGroups = groupApi::getGroups,
        heldGroups = groupDao::getUuids,
        declinedGroups = groupDao::getDeclinedUuids,
        vaultKeys = vaultRepository::getGroupKeys,
        listMessages = groupApi::getGroupMessages,
        acknowledge = groupApi::acknowledgeGroupMessages,
        isUnblockedContact = contactDao::isUnblocked,
        client = mlsIdentityRepository::client,
        save = groupDao::insertWithMembers,
        recordDeclined = { groupUuid -> groupDao.insertDeclined(DeclinedGroupEntity(groupUuid = groupUuid)) },
        backUp = groupKeyBackUp::backUp,
        fullName = userDao::getFullName,
    )

    /**
     * Runs every step, see [GroupSync], and says which groups it joined and what failed first.
     *
     * [contactsTakenIn] is whether the contact sync run just before took in all the server held,
     * see [ContactSyncPass.isTakenIn]. Until one has, somebody who is not a contact on this device
     * may be one it has yet to hear of, so their Welcome is left waiting rather than declined, see
     * [join], and not reported either: the contact sync reports its own failure.
     */
    suspend fun sync(contactsTakenIn: Boolean): GroupSyncPass = withGroupsLock {
        var failure: GroupSyncError? = null

        resendPending().onError { failure = failure ?: GroupSyncError.Network(it) }

        backUpPending().onError { failure = failure ?: GroupSyncError.Vault(it) }

        val joined = mutableListOf<GroupArrival>()

        takeIn(joined = joined, contactsTakenIn = contactsTakenIn)?.let { failure = failure ?: it }

        GroupSyncPass(joined = joined, failure = failure)
    }

    /**
     * Whether no group the server lists for the user holds a Welcome this device has yet to take
     * in. One could need the secret of one of the user's key packages, see
     * [mls.KeyPackageRepository]. False when the server could not be asked.
     *
     * Only the groups this device neither holds nor declined: what waits in the others was taken
     * in already, the Welcome joined or declined, see [join].
     *
     * Waits for a [sync] already running, whose Welcomes would otherwise still be listed. Only
     * looks: taking them in is [sync]'s.
     */
    suspend fun hasNoWelcomeWaiting(): Boolean = withGroupsLock {
        val listed = when (val list = listGroups()) {
            is Result.Success -> list.data
            is Result.Error -> return@withGroupsLock false
        }

        val known = heldGroups().toHashSet() + declinedGroups()

        listed.filterNot { it.uuid in known }.all { group ->
            val waiting = listMessages(group.uuid)

            waiting is Result.Success && waiting.data.messages.isEmpty()
        }
    }

    /**
     * Takes in every group the server lists that this device neither holds nor declined, see
     * [GroupSync], noting in [joined] those it joined, and returns the first failure: a group that
     * fails is left for the next sync, and the next group is taken in all the same.
     */
    private suspend fun takeIn(joined: MutableList<GroupArrival>, contactsTakenIn: Boolean): GroupSyncError? {
        val selfUuid = userUuid() ?: return GroupSyncError.NoSession

        // Before anybody is taken for a stranger: until the contacts are back after a log-out,
        // every one of them looks like one, and a group of theirs would be declined for good.
        restoreContacts().onError { return GroupSyncError.Contacts(it) }

        val listed = when (val list = listGroups()) {
            is Result.Success -> list.data
            is Result.Error -> return GroupSyncError.Network(list.error)
        }

        val known = heldGroups().toHashSet() + declinedGroups()
        val invitations = listed.filterNot { it.uuid in known }

        // Most syncs find nothing new, and reading the vault is a request of its own.
        if (invitations.isEmpty()) return null

        // Before anything is joined: a group whose key the vault holds was the user's before a
        // log-out, and a Welcome still waiting there was sealed to a key package that log-out
        // took with it.
        val groupKeys = when (val read = vaultKeys()) {
            is Result.Success -> read.data
            is Result.Error -> return GroupSyncError.Vault(read.error)
        }

        var failure: GroupSyncError? = null

        for (group in invitations) {
            // A pass stops between groups, never inside one: a Welcome joined is spent, and a
            // group joined and never saved would be MLS state nothing names.
            currentCoroutineContext().ensureActive()

            val groupKey = groupKeys[group.uuid]

            val taken = withContext(NonCancellable) {
                if (groupKey != null) {
                    restore(group = group, groupKey = groupKey)
                } else {
                    join(group = group, selfUuid = selfUuid, joined = joined, contactsTakenIn = contactsTakenIn)
                }
            }

            failure = failure ?: taken
        }

        return failure
    }

    /**
     * Brings back [group], which a log-out took off this device, with [groupKey], the key the vault
     * kept of it: its fields and its members, without its MLS state, which the log-out took for
     * good, see [GroupEntity.mlsGroupId]. The members are those the server lists, the tree that
     * named them having gone with that state.
     *
     * Fields that do not open under that key, as this group's, are not the ones its members sealed,
     * and the group is left where it was, on the server.
     */
    private suspend fun restore(group: GroupDto, groupKey: ByteArray): GroupSyncError? {
        val fields = group.sealedFields().unseal(groupKey = groupKey, groupUuid = group.uuid) ?: return null

        val kept = keep(
            group = group,
            fields = fields,
            memberUuids = group.members.map { it.userUuid },
            mlsGroupId = null,
            groupKey = groupKey,
            isBackedUp = true,
        )

        return if (kept) null else GroupSyncError.Storage
    }

    /**
     * Joins [group] from the Welcome waiting there, and notes it in [joined]; or declines it, see
     * [decline].
     *
     * The Welcome is read for what it says before anything is stored, see [inspectGroupWelcome],
     * since the join records the group as joined for good, deleted again or not: a Welcome joined
     * only to be refused, or left waiting, would leave the group unjoinable here. The inspection
     * refuses a Welcome to any group but [group], whose MLS id the library derives from its UUID,
     * as for the Welcome posted again under another group's UUID by any of its members; and no
     * channel or contact request can have taken that id. A member can create a group of their own
     * under [group]'s UUID, though, which takes the same id, and post its Welcome there: only the
     * Welcome the server wrote with the group, the first one waiting, is ever joined from, so a
     * Welcome refused as one already joined is to [group] itself, joined here before. It is
     * declined without being joined unless it holds up: the member who wrote it may add the user
     * to a group, see [mayAdd], whoever the server says sent it, and what they handed over opens
     * this group, see [open]. When only who wrote it does not hold up, it is left waiting instead
     * unless [contactsTakenIn], see [sync].
     *
     * Joined otherwise, and saved with the members the Welcome names, the user among them, rather
     * than those the server lists, see [joinGroupOf]. Joined and saved under [withGroupJoinLock],
     * so that no direct message is read in the group while the library holds it and the database
     * does not name it yet, see [message.DirectMessageSync].
     *
     * Not cancellable, which the caller sees to: once the join has run, the same Welcome is refused
     * ever after, the group being recorded as joined, and a group joined and not saved is lost.
     */
    private suspend fun join(
        group: GroupDto,
        selfUuid: String,
        joined: MutableList<GroupArrival>,
        contactsTakenIn: Boolean,
    ): GroupSyncError? {
        val messages = when (val list = listMessages(group.uuid)) {
            is Result.Success -> list.data.messages
            is Result.Error -> return GroupSyncError.Network(list.error)
        }

        // Written with the group itself, so it comes first, and the only one joined from: any
        // member can post a Welcome here after it, to a group of their own under this UUID, whose
        // id is this group's. None waiting is a group this device has no way into, ever: the
        // server hands a member no Welcome but that one, and the vault holds no key to it. Every
        // group declined before a log-out is one, after it.
        val welcome = messages.firstOrNull() ?: return decline(groupUuid = group.uuid, messages = messages)

        // The server stores a Welcome without reading it, so this is whatever its sender posted.
        val welcomeMessage = runCatching { Base64.Default.decode(welcome.ciphertext) }.getOrNull()
            ?: return decline(groupUuid = group.uuid, messages = messages)

        val client = when (val opened = client()) {
            is Result.Success -> opened.data
            is Result.Error -> return GroupSyncError.Identity(opened.error)
        }

        val inspection = try {
            client.inspectGroupWelcome(welcome = welcomeMessage, groupUuid = group.uuid)
        } catch (exception: MlsException) {
            // A Welcome that was never ours, was already used, is to another group, seats an
            // identity other than the one pinned for that member or more members than a group may
            // hold, is simply no way in. Storage failing says nothing about the Welcome, so that
            // one is tried again.
            return if (exception.isRetryable()) {
                GroupSyncError.Storage
            } else {
                decline(groupUuid = group.uuid, messages = messages)
            }
        }

        val committerMayAdd = try {
            mayAdd(client = client, userUuid = inspection.committerUserUuid)
        } catch (_: MlsException) {
            return GroupSyncError.Storage
        }

        if (!committerMayAdd) {
            // Declining is for good: not while they may be a contact this device has yet to hear of.
            return if (contactsTakenIn) decline(groupUuid = group.uuid, messages = messages) else null
        }

        val opened = open(group = group, inspection = inspection)
            ?: return decline(groupUuid = group.uuid, messages = messages)

        // From the join to the save under the join lock, see [join], and declined outside it:
        // declining asks the server.
        val kept = try {
            withGroupJoinLock {
                val mlsGroupId = client.joinGroupOf(
                    welcome = welcomeMessage,
                    groupUuid = group.uuid,
                    expectedMembers = inspection.memberUuids,
                )

                keep(
                    group = group,
                    fields = opened.fields,
                    memberUuids = inspection.memberUuids + selfUuid,
                    mlsGroupId = mlsGroupId,
                    groupKey = opened.groupKey,
                    isBackedUp = false,
                ).also { kept ->
                    // The Welcome is spent all the same, so the group goes, and the next sync finds
                    // the Welcome refused as one already joined.
                    if (!kept) client.deleteGroupQuietly(mlsGroupId = mlsGroupId)
                }
            }
        } catch (exception: MlsException) {
            // Past the inspection, the join refuses for good only a group too large to store, or one
            // seating a member pinned to another identity meanwhile, by a contact sync running
            // alongside say, which neither lock keeps out. Nothing can have taken the group's id: no
            // channel or contact request can be given it, and every Welcome to this group is joined
            // here, under [withGroupsLock].
            return if (exception.isRetryable()) {
                GroupSyncError.Storage
            } else {
                decline(groupUuid = group.uuid, messages = messages)
            }
        }

        if (!kept) return GroupSyncError.Storage

        joined += GroupArrival(
            groupUuid = group.uuid,
            title = opened.fields.title,
            adderUuid = inspection.committerUserUuid,
            adderFullName = fullName(inspection.committerUserUuid),
        )

        var failure: GroupSyncError? = null

        // One the vault would not take now, the next sync backs up.
        backUp(group.uuid, opened.groupKey).onError { failure = GroupSyncError.Vault(it) }

        // The Welcome alone: whatever came after it was sent in the group, for its members to read.
        // One whose acknowledgement does not go through stays listed with a group this device now
        // holds, which holds nothing back: the key package it was sealed to is spent.
        acknowledge(group.uuid, listOf(welcome.uuid)).onError { failure = failure ?: GroupSyncError.Network(it) }

        return failure
    }

    /**
     * What [group] opens to with the key its Welcome hands over, as [inspection] read it, or null
     * when it hands over none, or the fields do not open under it as this group's, see
     * [SealedGroupFields.unseal], which is also what refuses a key that is not 32 bytes.
     *
     * The key is the bare payload, see [GroupCreator]: the Welcome it came in is to this group and
     * no other, the inspection having refused any other, and only whoever wrote that Welcome can
     * have put it there.
     */
    private fun open(group: GroupDto, inspection: WelcomeInspection): OpenedGroup? {
        val groupKey = inspection.welcomePayload ?: return null

        val fields = group.sealedFields().unseal(groupKey = groupKey, groupUuid = group.uuid) ?: return null

        return OpenedGroup(fields = fields, groupKey = groupKey)
    }

    /**
     * Whether [userUuid] may add the user to a group: one of their contacts, unblocked, whose
     * identity this device pinned.
     *
     * The pin is what the inspection checked them against, the members of a group being joined
     * without pinning anybody, see [joinGroupOf]: for a contact this device never reached over MLS,
     * it would check them against nothing, and they would be whoever the server built the Welcome
     * as. A group the user creates asks the same of its invitees, see [GroupCreator].
     *
     * Throws [MlsException] when the pin could not be read.
     */
    private suspend fun mayAdd(client: MlsClient, userUuid: String): Boolean =
        isUnblockedContact(userUuid) && client.pinnedSignatureKeyFor(peerUuid = userUuid) != null

    /**
     * Declines the group [groupUuid] for good: every message waiting there, [messages], is
     * acknowledged, the Welcome among them, and the group recorded as declined once they are, see
     * [DeclinedGroupEntity]. Until then it is left for the next sync, which decides again.
     */
    private suspend fun decline(groupUuid: String, messages: List<GroupMessageDto>): GroupSyncError? {
        acknowledge(groupUuid, messages.map { it.uuid }).onError { return GroupSyncError.Network(it) }

        recordDeclined(groupUuid)

        return null
    }

    /**
     * Saves [group] with its [fields] and its members, [memberUuids], the owner the server holds as
     * their admin, and says whether it was saved.
     */
    private suspend fun keep(
        group: GroupDto,
        fields: GroupFields,
        memberUuids: List<String>,
        mlsGroupId: ByteArray?,
        groupKey: ByteArray,
        isBackedUp: Boolean,
    ): Boolean {
        val entity = GroupEntity(
            uuid = group.uuid,
            title = fields.title,
            description = fields.description,
            currency = fields.currency,
            ownerUuid = group.ownerUuid,
            mlsGroupId = mlsGroupId,
            groupKey = groupKey,
            isBackedUp = isBackedUp,
        )

        val members = memberUuids.map {
            GroupMemberEntity(userUuid = it, groupUuid = group.uuid, isAdmin = it == group.ownerUuid)
        }

        return try {
            save(entity, members)

            true
        } catch (_: Exception) {
            false
        }
    }
}

/** A group's fields opened, with the key that opened them, see [GroupSync.open]. */
private class OpenedGroup(val fields: GroupFields, val groupKey: ByteArray)

/** The fields of this group as the server holds them, sealed. */
private fun GroupDto.sealedFields() = SealedGroupFields(title = title, description = description, currency = currency)
