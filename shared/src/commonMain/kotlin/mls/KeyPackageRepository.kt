package mls

import authenticationUtils.clearKeyPackagesToPublish
import authenticationUtils.hasKeyPackagesToPublish
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

    /** Whether [publishIfOwed] has already tried in this process. */
    private var hasTriedOwed = false

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
            is Result.Success -> {
                clearKeyPackagesToPublish()

                Result.Success(Unit)
            }

            is Result.Error -> Result.Error(KeyPackageError.Network(publication.error))
        }
    }

    /**
     * Publishes a fresh bundle if none has gone up since the user logged in, see
     * [authenticationUtils.setKeyPackagesToPublish].
     *
     * Login publishes too, but only logs a failure, and the next login is up to the end of the
     * session away. That is what this catches.
     *
     * Tries once per process, so at most once per launch. Generating a bundle stores the
     * secret of every key package in it, which nothing deletes until a Welcome uses it or the
     * session ends, so a publication that kept failing would otherwise pile up a bundle's
     * worth of secrets nobody can use every time it was tried. Sending the same bundle again
     * instead is no better: a publication whose response was lost may have landed, and some
     * of its one-time packages been handed out since, which would put them back on offer.
     *
     * Not on a timer: a session ends ninety days after the login that started it, and a
     * login publishes key packages that last exactly as long. Publishing again mid-session
     * would only leave packages on the server that outlive it, whose secrets the wipe that
     * ends the session takes, so a peer could build a group from one the user never joins.
     */
    suspend fun publishIfOwed(): EmptyResult<KeyPackageError> {
        if (hasTriedOwed || !hasKeyPackagesToPublish()) return Result.Success(Unit)

        hasTriedOwed = true

        return publish()
    }
}
