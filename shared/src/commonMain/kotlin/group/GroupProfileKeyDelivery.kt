package group

import contact.ProfileKeyDelivery
import contact.ProfileKeyUpdate
import database.data.local.dao.GroupDao
import database.data.local.entity.GroupEntity
import message.ResendScheduler
import mls.MlsIdentityError
import mls.MlsIdentityRepository
import mls.encryptApplicationMessage
import mls.isRetryable
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import uniffi.lib_duckcount.profileEncryptionKeyPartialHash
import util.EmptyResult
import util.Result
import vault.ProfileKeyVault

/**
 * Posts the key to the user's own profile into every group of theirs owed it, see
 * [GroupEntity.handedProfileKeyHash], so that the members who are not their contacts can read
 * their name, see [MemberProfileRepository].
 *
 * The creator of a group hands every member's key to the others as he seats somebody, the user's
 * among them, see [profileKeysToHandOut], and that one is the key he was handed last, which can be
 * older than the user's own, or missing. A member holding a key for the user already, learned in
 * another group, keeps it over the one he relays, which may be older still. So the user posts
 * theirs into each group they join, and into each again as its creator seats somebody, see
 * [GroupSync.takeProfileKeys]. And the user's key rotates once they remove a contact, see
 * `ProfileRepository.rotateProfileKey`, which leaves every group holding the old one: the contacts
 * get the new one one by one, see [ProfileKeyDelivery], and each group gets it here, in one post
 * its members all read. Each takes it as the owner's own, which replaces whatever key was held for
 * the user, the server having stored it after every post of theirs before it, in this group or any
 * other, see [MemberProfileRepository.takeOwnerKey].
 *
 * Into the groups the members can trust what the post says of its sender in, see
 * [GroupDao.getOwedProfileKey]: one the user is still in, whose MLS state this device holds, and
 * whose creator, who seated every member, is not blocked. Not while a change of the user's to the
 * group is waiting on the server, its creation or a change to its members: the post would go out
 * in an epoch the group is leaving, or into a group the server does not hold yet. Nor into one
 * [handOut] is told the sync could not catch up with, whose members are past the epoch this device
 * is in: those it seated since could not read it.
 *
 * Who is owed it is told by the hash of the current key the device keeps, as for the contacts, so
 * a sync that owes no group, nearly every one, reads nothing from the vault.
 *
 * A post the server took, or one with nobody else in the group to read it, leaves the group
 * holding the key; a refusal for the user's allowance of posts books the pass again for when it
 * reopens, see [ResendScheduler], and stops it there, as every other post would be refused too.
 * Anything else leaves the group owed for the next sync: a post sent twice is read twice, and
 * changes nothing the second time.
 *
 * To be called under [withGroupsLock], which it does not take itself, see [GroupSync.sync]: the
 * MLS state each post is encrypted with is the one the sync reads the group's messages with.
 *
 * [owedGroups] is [GroupDao.getOwedProfileKey], [setHanded] is [GroupDao.setHandedProfileKeyHash],
 * [client] is [MlsIdentityRepository.client] and [post] is [GroupApi.postMessage], or what a test
 * stands in for any of them.
 */
class GroupProfileKeyDelivery internal constructor(
    private val profileKeyVault: ProfileKeyVault,
    private val owedGroups: suspend (profileKeyHash: String) -> List<GroupEntity>,
    private val setHanded: suspend (groupUuid: String, handedProfileKeyHash: String) -> Unit,
    private val client: suspend () -> Result<MlsClient, MlsIdentityError>,
    private val post: suspend (groupUuid: String, message: ByteArray) -> GroupPostAnswer,
    private val resendScheduler: ResendScheduler,
) {
    constructor(
        profileKeyVault: ProfileKeyVault,
        groupDao: GroupDao,
        mlsIdentityRepository: MlsIdentityRepository,
        groupApi: GroupApi,
        resendScheduler: ResendScheduler,
    ) : this(
        profileKeyVault = profileKeyVault,
        owedGroups = groupDao::getOwedProfileKey,
        setHanded = { groupUuid, handedProfileKeyHash ->
            groupDao.setHandedProfileKeyHash(uuid = groupUuid, handedProfileKeyHash = handedProfileKeyHash)
        },
        client = mlsIdentityRepository::client,
        post = groupApi::postMessage,
        resendScheduler = resendScheduler,
    )

    /**
     * Posts the current key into every group owed it, see [GroupProfileKeyDelivery], but those
     * whose UUID is in [behind], recording each that holds it then.
     *
     * Every group owed is attempted until the server cannot be reached or the user's allowance of
     * posts runs out, either of which stops the pass, and the first failure is reported. A refusal
     * for the allowance is not one: the resend it books is what comes of it. Nor is a `404`, see
     * [GroupPostAnswer.NotMember].
     */
    suspend fun handOut(behind: Set<String>): EmptyResult<GroupSyncError> {
        // The key itself, when the hash had to be read from it: whichever group is owed is posted it.
        var readKey: ByteArray? = null

        val currentHash = profileKeyVault.getCachedProfileEncryptionKeyPartialHash()
            ?: when (val read = profileKeyVault.getOrCreateProfileEncryptionKey()) {
                is Result.Success -> profileEncryptionKeyPartialHash(read.data).also { readKey = read.data }
                is Result.Error -> return Result.Error(GroupSyncError.Vault(read.error))
            }

        val owed = owedGroups(currentHash).filterNot { it.uuid in behind }

        if (owed.isEmpty()) return Result.Success(Unit)

        val profileEncryptionKey = readKey
            ?: when (val read = profileKeyVault.getOrCreateProfileEncryptionKey()) {
                is Result.Success -> read.data
                is Result.Error -> return Result.Error(GroupSyncError.Vault(read.error))
            }

        val client = when (val opened = client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(GroupSyncError.Identity(opened.error))
        }

        // What each group is recorded as holding is the key that went out, not the one the hash
        // named: a rotation in between leaves it owed the newer one, and the next pass posts it.
        val handedHash = profileEncryptionKeyPartialHash(profileEncryptionKey)
        val payload = ProfileKeyUpdate(profileEncryptionKey = profileEncryptionKey).serialize()

        var failure: GroupSyncError? = null

        for (group in owed) {
            val mlsGroupId = checkNotNull(group.mlsGroupId) { "Only a group whose MLS state is held is owed a post." }

            // Encrypting spends a key of the user's in the group, which is all the state it
            // changes: a post that never goes out is encrypted again by the next pass.
            val message = try {
                client.encryptApplicationMessage(mlsGroupId = mlsGroupId, payload = payload)
            } catch (exception: MlsException) {
                // One the library refuses for good is a group whose state it can no longer use,
                // which nothing is ever posted into again: left owed, and not reported.
                if (exception.isRetryable()) failure = failure ?: GroupSyncError.Storage

                continue
            }

            when (val answer = post(group.uuid, message)) {
                // Nobody else in the group, nobody to hold an older key for the user either.
                GroupPostAnswer.Posted, GroupPostAnswer.NoOtherMembers -> setHanded(group.uuid, handedHash)

                // Removed from it, which the next sync reads, or the proxy answering for a server
                // being redeployed: the next pass posts again, if it is still owed then.
                GroupPostAnswer.NotMember -> Unit

                is GroupPostAnswer.RateLimited -> {
                    resendScheduler.resendAfter(seconds = answer.retryAfterSeconds)

                    break
                }

                // The next post would fare no better.
                is GroupPostAnswer.Failed -> return Result.Error(failure ?: GroupSyncError.Network(answer.error))
            }
        }

        return failure?.let { Result.Error(it) } ?: Result.Success(Unit)
    }
}
