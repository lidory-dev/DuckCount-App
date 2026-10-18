package contact

import channel.DirectChannelError
import channel.DirectChannelRepository
import channel.Reply
import database.data.local.dao.ContactDao
import database.data.local.dao.PendingProfileKeyDao
import database.data.local.entity.PendingProfileKeyEntity
import database.data.local.entity.ProfileKeyRequestBudgetEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import message.DirectMessageSender
import message.DirectSendError
import message.neverReachedServer
import uniffi.lib_duckcount.profileEncryptionKeyPartialHash
import util.EmptyResult
import util.NetworkError
import util.Result
import util.backoffFor
import util.nowEpochMilliseconds
import util.spendMessageBudget
import vault.ProfileKeyVault

/**
 * Asks a contact for the key to their profile when the one this device holds no longer opens
 * their latest version, and answers a contact who asks for the user's.
 *
 * The key to a profile is pushed to its owner's contacts as it rotates, see
 * [ProfileKeyDelivery], and the push can go missing with neither side the wiser: sent into a
 * group the contact lost to a log-out or a reinstall, it is unreadable to them and acknowledged
 * as such, while the owner's device took the send for the key handed. Every way it can go
 * missing ends the same, the contact holding a key that opens an older version of the profile,
 * and the server says so as it serves one, see
 * [ServedUserProfileDto.isLatest][profile.ServedUserProfileDto.isLatest]. So whichever read of
 * a contact's profile finds it is not the latest, or finds none, records the contact as missing
 * their key, see [PendingProfileKeyEntity], and this asks them for it. Not every such read means
 * a key went missing, the newer version being at times under a key the contact never took
 * themselves, see the same: they then answer with the very key this device holds, which
 * settles it too, see
 * [ContactRepository.receiveProfileKey][database.data.repository.ContactRepository.receiveProfileKey].
 * Pulled as well as pushed, as the keys to one-time expenses are, see
 * `OneTimeExpenseSync.requestMissingKeys`, which this is modelled on.
 *
 * Found out at a read, and no sooner: a key lost after this device read the contact's name is
 * noticed at the next read of their profile, which comes once the name is gone, with a log-out
 * or a reinstall, see `ContactDao.getDueForRead`. Nothing is read with the key until then, a
 * name being read once.
 *
 * Only a contact is asked, never somebody whose contact request is waiting, whose name is read
 * off whatever key the request carried, nor the user, who is never their own contact. And only
 * a contact is answered, see [answer].
 *
 * [send] is [DirectMessageSender.send], building a channel to a contact this device holds none
 * to, one restored from the vault for instance, which mends the channel on the way. [claimReply]
 * and [replyIn] are [DirectChannelRepository.claimReply] for a [Reply.PROFILE_KEY] and
 * [DirectMessageSender.replyIn]. All three are what a test stands in for.
 */
class ProfileKeyPull internal constructor(
    private val pendingProfileKeyDao: PendingProfileKeyDao,
    private val contactDao: ContactDao,
    private val profileKeyVault: ProfileKeyVault,
    private val send: suspend (recipientUserUuid: String, payload: ByteArray) -> EmptyResult<DirectSendError>,
    private val claimReply: suspend (peerUuid: String) -> Boolean,
    private val replyIn: suspend (
        recipientUserUuid: String,
        mlsGroupId: ByteArray,
        payload: ByteArray,
    ) -> EmptyResult<DirectSendError>,
) {
    constructor(
        pendingProfileKeyDao: PendingProfileKeyDao,
        contactDao: ContactDao,
        profileKeyVault: ProfileKeyVault,
        directMessageSender: DirectMessageSender,
        directChannelRepository: DirectChannelRepository,
    ) : this(
        pendingProfileKeyDao = pendingProfileKeyDao,
        contactDao = contactDao,
        profileKeyVault = profileKeyVault,
        // As plain messages, both ways, so the phone they reach wakes and syncs, which takes the
        // request, or the key, in.
        send = { recipientUserUuid, payload ->
            directMessageSender.send(recipientUserUuid = recipientUserUuid, payload = payload)
        },
        claimReply = { peerUuid ->
            directChannelRepository.claimReply(peerUuid = peerUuid, reply = Reply.PROFILE_KEY)
        },
        replyIn = { recipientUserUuid, mlsGroupId, payload ->
            directMessageSender.replyIn(
                recipientUserUuid = recipientUserUuid,
                mlsGroupId = mlsGroupId,
                payload = payload,
            )
        },
    )

    /**
     * Serializes [requestMissingKeys], which every contact sync reaches, from a screen and a push
     * at once. Each would read the same contacts as due, ask them twice, and spend their budget
     * of requests twice over, see [claimRequest].
     */
    private val mutex = Mutex()

    /**
     * Asks each contact this device is missing the current profile key of for it, blocked ones
     * aside, once the backoff since the last try allows, see [backoffFor].
     *
     * Asking again is the backoff's to decide, and nothing else's: no read of the contact's
     * profile puts back what was tried, neither the one a new key of theirs coming in calls for
     * nor, for a contact with no name, the one every sync makes, see
     * [PendingProfileKeyEntity.profileEncryptionKey]. What settles it is a read that opens their
     * latest version, or their answer handing over the very key this device holds. A
     * contact whose account is gone, or who never answers, is asked ever more rarely, down to
     * once a day, and one the server says is gone never again, see
     * [PendingProfileKeyEntity.isAccountGone].
     *
     * Each contact is also asked out of a budget, as a creditor is for the keys to expenses: two
     * requests at once and one more an hour after that, see [claimRequest]. A contact whose
     * budget is spent is left due, and asked at the first pass it allows.
     *
     * One message per contact, and the first refusal for the user's allowance of messages stops
     * the pass, the rest waiting for the next one. Any other failure counts as a try, so the
     * contact waits out the backoff, and is reported unless no retry can change it.
     */
    suspend fun requestMissingKeys(): EmptyResult<ContactSyncError> = mutex.withLock {
        val askable = pendingProfileKeyDao.getAskable()

        if (askable.isEmpty()) return@withLock Result.Success(Unit)

        val now = nowEpochMilliseconds()

        val due = askable.filter { row ->
            row.lastRequestedAt == null || now - row.lastRequestedAt >= backoffFor(row.requestCount)
        }

        var failure: ContactSyncError? = null
        var sent = 0

        for (row in due) {
            if (sent >= MAX_CONTACTS_PER_PASS) break

            // Read before it is spent, to be put back should the request never leave this device.
            val budgetFullAt = pendingProfileKeyDao.getRequestBudgetFullAt(peerUuid = row.userUuid)

            if (!claimRequest(peerUuid = row.userUuid, budgetFullAt = budgetFullAt, now = now)) continue

            val request = send(row.userUuid, ProfileKeyRequest.serialize())

            if (request is Result.Success) {
                pendingProfileKeyDao.markRequested(userUuid = row.userUuid, requestedAt = now)

                sent++

                continue
            }

            val error = (request as Result.Error).error

            // Given back when it certainly never left this device: kept, it would spend the budget
            // of a contact this device cannot reach yet at every try with nothing sent.
            if (error.neverReachedServer()) {
                pendingProfileKeyDao.setRequestBudget(
                    budget = ProfileKeyRequestBudgetEntity(
                        peerUuid = row.userUuid,
                        // Never spent before, it goes back as a moment already past.
                        budgetFullAt = budgetFullAt ?: now,
                    ),
                )
            }

            when (error) {
                // The server knows of no such user, which for an account is final.
                DirectSendError.RecipientGone -> pendingProfileKeyDao.markAccountGone(userUuid = row.userUuid)

                // Sending more only pushes the count further past the limit. The rest wait for
                // the next pass.
                DirectSendError.RateLimited -> return@withLock Result.Error(
                    ContactSyncError.Network(NetworkError.TOO_MANY_REQUESTS),
                )

                // No channel could be built to them, which the account being gone is answered with
                // too; somebody the vault gave back unpinned; somebody whose identity is in doubt.
                // None is anything a retry soon would change, or anything to report.
                DirectSendError.Channel(DirectChannelError.Unreachable),
                DirectSendError.Channel(DirectChannelError.NotPinned),
                DirectSendError.Channel(DirectChannelError.IdentityChanged),
                    -> pendingProfileKeyDao.markRequested(userUuid = row.userUuid, requestedAt = now)

                else -> {
                    pendingProfileKeyDao.markRequested(userUuid = row.userUuid, requestedAt = now)

                    failure = failure ?: error.toContactSyncError()
                }
            }
        }

        failure?.let { Result.Error(it) } ?: Result.Success(Unit)
    }

    /**
     * Hands [requesterUuid], who asked for it in the group [mlsGroupId], the current key to the
     * user's profile, see [ProfileKeyRequest].
     *
     * Only a contact is answered, blocked ones aside, and anybody else's request is dropped with
     * no answer: the key opens the profile as it stands, which is the user's contacts' alone to
     * read. Somebody the user removed, who still holds an older key, gets no answer either. That
     * a newer version exists they know already, the server's read having told them, see
     * [ServedUserProfileDto.isLatest][profile.ServedUserProfileDto.isLatest], and their removal
     * having made it; its key, and so what it says, this device never hands them.
     *
     * Every answer is one of the user's own allowance of messages, and the contact decides how
     * often they ask, so it is claimed from their budget of replies first, see
     * [DirectChannelRepository.claimReply], and a request past it is dropped the way a stranger's
     * is: an honest contact asks again on their own backoff. It goes back in the group the request
     * was authenticated in, as the keys to expenses do, see `OneTimeExpenseMessageHandler.answer`.
     *
     * A contact handed the key is recorded as holding it, see
     * [ContactEntity.handedProfileKeyHash][database.data.local.entity.ContactEntity.handedProfileKeyHash],
     * so the next [ProfileKeyDelivery] does not push it to them again.
     */
    internal suspend fun answer(requesterUuid: String, mlsGroupId: ByteArray) {
        if (!contactDao.isUnblocked(userUuid = requesterUuid)) return

        if (!claimReply(requesterUuid)) return

        val profileEncryptionKey = when (val read = profileKeyVault.getOrCreateProfileEncryptionKey()) {
            is Result.Success -> read.data
            // Asked again on their backoff.
            is Result.Error -> return
        }

        val replied = replyIn(
            requesterUuid,
            mlsGroupId,
            ProfileKeyUpdate(profileEncryptionKey = profileEncryptionKey).serialize(),
        )

        if (replied is Result.Success) {
            contactDao.setHandedProfileKeyHash(
                userUuid = requesterUuid,
                handedProfileKeyHash = profileEncryptionKeyPartialHash(profileEncryptionKey),
            )
        }
    }

    /**
     * Whether this device may ask [peerUuid], whose budget is whole again at [budgetFullAt], for
     * their profile key now, which it then records it does, see [ProfileKeyRequestBudgetEntity].
     *
     * Claimed before the request is sent, and kept when the send fails unless it certainly never
     * left this device, as `OneTimeExpenseSync.claimRequest` does. Only ever under [mutex], since
     * the budget is read before it is written.
     */
    private suspend fun claimRequest(peerUuid: String, budgetFullAt: Long?, now: Long): Boolean {
        val fullAt = spendMessageBudget(
            budgetFullAt = budgetFullAt,
            now = now,
            onlyWhenWhole = false,
        ) ?: return false

        pendingProfileKeyDao.setRequestBudget(
            budget = ProfileKeyRequestBudgetEntity(peerUuid = peerUuid, budgetFullAt = fullAt),
        )

        return true
    }
}

/** How many contacts are asked in one pass, so a re-login does not ask everyone at once. */
private const val MAX_CONTACTS_PER_PASS = 3
