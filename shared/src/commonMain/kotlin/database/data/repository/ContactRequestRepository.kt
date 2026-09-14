package database.data.repository

import app.lidory.duckcount.hashDiscoveryUsername
import authenticationUtils.getAuthenticatedUserUuid
import contact.ContactCard
import contact.ContactRequestEnvelope
import contact.SendContactRequestError
import database.data.local.dao.ContactRequestDao
import database.data.mappers.toContactRequestEntity
import database.data.remote.ContactApi
import database.data.remote.SendContactRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import mls.MlsIdentityRepository
import uniffi.lib_duckcount.InviteeKeyPackage
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import util.EmptyResult
import util.NetworkError
import util.Result
import vault.VaultRepository
import kotlin.io.encoding.Base64

/**
 * The user's contact requests: the ones addressed to them, and the ones they send.
 *
 * For the incoming ones the server is the only source: the local table is a cache, so
 * the screens that count requests keep working offline and across restarts.
 */
class ContactRequestRepository(
    private val contactRequestDao: ContactRequestDao,
    private val contactApi: ContactApi,
    private val vaultRepository: VaultRepository,
    private val mlsIdentityRepository: MlsIdentityRepository,
) {
    fun getPendingCount(): Flow<Int> {
        return contactRequestDao.getCountAsFlow()
    }

    /**
     * Brings the cache in line with what the server holds.
     *
     * A failure leaves the cache exactly as it was, so the caller keeps showing the
     * count it already had.
     */
    suspend fun refresh(): EmptyResult<NetworkError> {
        return when (val response = contactApi.getContactRequests()) {
            is Result.Error -> response

            is Result.Success -> {
                contactRequestDao.replaceAll(
                    contactRequests = response.data.requests.map { it.toContactRequestEntity() },
                )

                Result.Success(Unit)
            }
        }
    }

    /**
     * Asks the holder of the discovery [username] to become a contact, handing them the
     * user's profile encryption key.
     *
     * The request creates the MLS group the two of them will share. The lookup hands over
     * one of the recipient's key packages, the group is created with them in it, and the
     * key travels encrypted to that group next to the Welcome that lets them join it.
     * Nothing else is recorded: the group, kept by the library on this device, is the
     * relationship, which is also why a request the server turns down deletes it again.
     */
    suspend fun sendContactRequest(username: String): EmptyResult<SendContactRequestError> {
        val digest = hashDiscoveryUsername(username = username)
            ?: return Result.Error(SendContactRequestError.InvalidUsername)

        val recipient = when (val lookup = contactApi.lookupContact(username = digest)) {
            is Result.Success -> lookup.data
            is Result.Error -> return Result.Error(lookup.error.toSendContactRequestError())
        }

        // The server only refuses a request to oneself once it is sent, after the lookup
        // has already spent one of the user's own key packages. Nothing more goes on it.
        if (recipient.userUuid == getAuthenticatedUserUuid()) {
            return Result.Error(SendContactRequestError.Self)
        }

        // The server stores key packages without reading them, so this is whatever the
        // recipient's client published.
        val keyPackage = runCatching { Base64.Default.decode(recipient.keyPackage) }.getOrNull()
            ?: return Result.Error(SendContactRequestError.Crypto)

        // Everything that can fail without leaving anything behind comes first: once the
        // group exists, every failure has to delete it.
        val profileEncryptionKey = when (val read = vaultRepository.getOrCreateProfileEncryptionKey()) {
            is Result.Success -> read.data
            is Result.Error -> return Result.Error(SendContactRequestError.Vault(read.error))
        }

        val client = when (val opened = mlsIdentityRepository.client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(SendContactRequestError.Identity(opened.error))
        }

        val group = try {
            withContext(Dispatchers.IO) {
                client.createGroup(
                    invitees = listOf(
                        InviteeKeyPackage(userUuid = recipient.userUuid, keyPackage = keyPackage),
                    ),
                )
            }
        } catch (_: MlsException.IdentityKeyMismatch) {
            return Result.Error(SendContactRequestError.IdentityChanged)
        } catch (_: MlsException) {
            return Result.Error(SendContactRequestError.Crypto)
        }

        val envelope = try {
            withContext(Dispatchers.IO) {
                val message = client.loadGroup(mlsGroupId = group.mlsGroupId).use { mlsGroup ->
                    mlsGroup.encryptApplicationMessage(
                        payload = ContactCard(profileEncryptionKey = profileEncryptionKey).serialize(),
                    )
                }

                // One invitee, so one Welcome: the library fails the creation rather than
                // leave an invitee without theirs.
                ContactRequestEnvelope(welcome = group.welcomes.single().welcome, message = message)
            }
        } catch (_: MlsException) {
            client.deleteGroupQuietly(mlsGroupId = group.mlsGroupId)

            return Result.Error(SendContactRequestError.Crypto)
        }

        val send = contactApi.sendContactRequest(
            body = SendContactRequestBody(
                recipientUserUuid = recipient.userUuid,
                ciphertext = envelope.serialize(),
            ),
        )

        if (send is Result.Error) {
            client.deleteGroupQuietly(mlsGroupId = group.mlsGroupId)

            return Result.Error(send.error.toSendContactRequestError())
        }

        return Result.Success(Unit)
    }
}

/**
 * Deletes a group the request that created it will never deliver.
 *
 * A failure is not reported: the request has already failed for a reason the user can
 * act on, and a group nobody else ever joined holds nothing worth protecting.
 */
private suspend fun MlsClient.deleteGroupQuietly(mlsGroupId: ByteArray) {
    withContext(Dispatchers.IO) {
        try {
            deleteGroup(mlsGroupId = mlsGroupId)
        } catch (_: MlsException) {
            // See above.
        }
    }
}

/** Maps the lookup's and the request's own refusals, the rest being plain network errors. */
private fun NetworkError.toSendContactRequestError(): SendContactRequestError = when (this) {
    NetworkError.NOT_FOUND -> SendContactRequestError.NotFound
    NetworkError.CONFLICT -> SendContactRequestError.AlreadyPending
    NetworkError.TOO_MANY_REQUESTS -> SendContactRequestError.RateLimited
    else -> SendContactRequestError.Network(this)
}
