package group

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mls.ConsumeKeyPackageDto
import mls.pinnedSignatureKeyFor
import uniffi.lib_duckcount.InviteeKeyPackage
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import util.NetworkError
import util.Result
import kotlin.io.encoding.Base64

/**
 * How many invitees' key packages are asked for at once: enough that a hundred members do not
 * take a hundred round trips, few enough not to open a hundred connections.
 */
private const val KEY_PACKAGE_FETCHES_AT_ONCE = 8

/**
 * Why some of the users to be put in a group cannot be, whether it is being created, see
 * [GroupCreator], or added to, see [GroupMembershipChanger].
 */
internal sealed interface InviteeRefusal {

    /** Not contacts of the user, or contacts this device pinned no identity for yet. */
    class NotContacts(val userUuids: Set<String>) : InviteeRefusal

    /** Contacts the vault blocks, see [database.data.local.entity.IdentityConflictEntity]. */
    class IdentityChanged(val userUuids: Set<String>) : InviteeRefusal

    /** Their pins could not be read. */
    data object Storage : InviteeRefusal
}

/**
 * Why [inviteeUuids] cannot all be put in a group, or null when they can: each of them has to be
 * one of [contacts], none of them [blocked], with an identity this device pinned, as for a
 * channel to them, see [channel.DirectChannelRepository.ensureChannel]. Building the group would
 * otherwise pin them from whichever key package the server served, letting it decide who they are.
 */
internal suspend fun MlsClient.inviteeRefusalOf(
    inviteeUuids: Set<String>,
    contacts: suspend () -> List<String>,
    blocked: suspend () -> List<String>,
): InviteeRefusal? {
    val notContacts = inviteeUuids - contacts().toSet()

    if (notContacts.isNotEmpty()) return InviteeRefusal.NotContacts(userUuids = notContacts)

    // A key package matching the identity this device pinned for them would go through,
    // when it is the one the vault disagrees with. See IdentityConflictEntity.
    val blockedInvitees = inviteeUuids.intersect(blocked().toSet())

    if (blockedInvitees.isNotEmpty()) return InviteeRefusal.IdentityChanged(userUuids = blockedInvitees)

    val notPinned = try {
        inviteeUuids.filterTo(HashSet()) { pinnedSignatureKeyFor(peerUuid = it) == null }
    } catch (_: MlsException) {
        return InviteeRefusal.Storage
    }

    return if (notPinned.isEmpty()) null else InviteeRefusal.NotContacts(userUuids = notPinned)
}

/** What asking for the key packages of a group's invitees came to, see [fetchKeyPackages]. */
internal sealed interface KeyPackageFetch {

    /** One key package for each of them. */
    class Fetched(val keyPackages: List<InviteeKeyPackage>) : KeyPackageFetch

    /**
     * Everybody who has none: they never published one, or their account is gone, which the
     * server answers the same way, or what it served is not even Base64.
     */
    class Unreachable(val userUuids: Set<String>) : KeyPackageFetch

    /** The server could not be asked for some, which may go through next time. */
    class Failed(val error: NetworkError) : KeyPackageFetch
}

/**
 * One key package for each of [inviteeUuids], a few at a time, with [consumeKeyPackage], see
 * [mls.KeyPackageApi.consumeKeyPackage], or why not all of them could be had, see
 * [KeyPackageFetch].
 *
 * Each one handed out is spent, so those of a change that fails go to waste. A user publishes a
 * pool of them, and the last-resort one is never spent.
 */
internal suspend fun fetchKeyPackages(
    inviteeUuids: Set<String>,
    consumeKeyPackage: suspend (userUuid: String) -> Result<ConsumeKeyPackageDto, NetworkError>,
): KeyPackageFetch {
    val permits = Semaphore(permits = KEY_PACKAGE_FETCHES_AT_ONCE)

    val fetched = coroutineScope {
        inviteeUuids.map { userUuid ->
            async { userUuid to permits.withPermit { consumeKeyPackage(userUuid) } }
        }.awaitAll()
    }

    val keyPackages = mutableListOf<InviteeKeyPackage>()
    val unreachable = mutableSetOf<String>()

    fetched.forEach { (userUuid, consumed) ->
        when (consumed) {
            is Result.Success -> {
                // The server stores key packages without reading them, so this is whatever the
                // invitee's client published.
                val keyPackage = runCatching { Base64.Default.decode(consumed.data.keyPackage) }.getOrNull()

                if (keyPackage == null) {
                    unreachable += userUuid
                } else {
                    keyPackages += InviteeKeyPackage(userUuid = userUuid, keyPackage = keyPackage)
                }
            }

            is Result.Error -> when (consumed.error) {
                // They published none, or their account is gone, as for a channel.
                NetworkError.NOT_FOUND -> unreachable += userUuid

                else -> return KeyPackageFetch.Failed(error = consumed.error)
            }
        }
    }

    if (unreachable.isNotEmpty()) return KeyPackageFetch.Unreachable(userUuids = unreachable)

    return KeyPackageFetch.Fetched(keyPackages = keyPackages)
}
