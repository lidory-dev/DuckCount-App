package mls

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import uniffi.lib_duckcount.MlsException
import util.EmptyResult
import util.Result
import kotlin.io.encoding.Base64

/**
 * How many one-time key packages a publication carries.
 *
 * Each one holds a fresh ML-KEM key pair whose secret is kept on the device until a peer
 * uses it, so the library advises a few dozen rather than the 100 the server accepts. The
 * pool is replaced at every publication anyway, which is when it gets refilled.
 */
private const val ONE_TIME_KEY_PACKAGE_COUNT = 20u

/**
 * The user's published MLS key packages.
 *
 * A key package is what lets a peer add the user to a group, a contact request included,
 * without the user being online. Each holds a secret that exists on this device alone,
 * which is why a publication replaces everything published before: after a log-out or a
 * reinstall, the packages still on the server can no longer be opened by anyone.
 */
class KeyPackageRepository(
    private val keyPackageApi: KeyPackageApi,
    private val mlsIdentityRepository: MlsIdentityRepository,
) {

    /**
     * Generates a fresh bundle, last-resort key package included, and publishes it.
     */
    suspend fun publish(): EmptyResult<KeyPackageError> {
        val client = when (val opened = mlsIdentityRepository.client()) {
            is Result.Success -> opened.data
            is Result.Error -> return Result.Error(KeyPackageError.Identity(opened.error))
        }

        val bundle = try {
            withContext(Dispatchers.IO) {
                client.generateKeyPackages(oneTimeCount = ONE_TIME_KEY_PACKAGE_COUNT)
            }
        } catch (_: MlsException) {
            return Result.Error(KeyPackageError.Crypto)
        }

        val publication = keyPackageApi.publishKeyPackages(
            userUuid = client.userUuid(),
            body = KeyPackagesPublicationDto(
                lastResortKeyPackage = Base64.Default.encode(bundle.lastResortKeyPackage),
                oneTimeKeyPackages = bundle.oneTimeKeyPackages.map { Base64.Default.encode(it) },
            ),
        )

        return when (publication) {
            is Result.Success -> Result.Success(Unit)
            is Result.Error -> Result.Error(KeyPackageError.Network(publication.error))
        }
    }
}
