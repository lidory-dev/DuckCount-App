package mls

import authenticationUtils.getKeyPackagesPublishedAt
import authenticationUtils.setKeyPackagesPublishedAt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import uniffi.lib_duckcount.MlsException
import util.EmptyResult
import util.Result
import util.nowEpochMilliseconds
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
 * How old the last publication gets before the next one is due.
 *
 * A third of the ninety days a key package lasts (`KEY_PACKAGE_LIFETIME` in `lib-duckcount`),
 * so an install opened only every couple of months still gets to replace its packages
 * before they run out.
 */
private const val REPUBLISH_AFTER_MILLISECONDS = 30L * 24 * 60 * 60 * 1000

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

    /** Whether [publishIfDue] has already tried in this process. */
    private var hasTriedDue = false

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
                setKeyPackagesPublishedAt(publishedAt = nowEpochMilliseconds())

                Result.Success(Unit)
            }

            is Result.Error -> Result.Error(KeyPackageError.Network(publication.error))
        }
    }

    /**
     * Publishes a fresh bundle when the last one is due for replacing, or none has gone up
     * since the user logged in, see [authenticationUtils.setKeyPackagesPublishedAt].
     *
     * A session lasts as long as it is used, and a key package ninety days, so the ones a
     * login put up would run out under a session still going, and nobody could start a
     * channel with the user any more: a contact request or an expense key sent their way
     * would fail on them. So they are replaced once they are [REPUBLISH_AFTER_MILLISECONDS]
     * old. Each publication leaves behind the secrets of the packages it replaced that nobody
     * used, which nothing deletes until the session ends.
     *
     * It also catches a login whose own publication failed, which only logs it.
     *
     * Tries once per process, so at most once per launch. Generating a bundle stores the
     * secret of every key package in it, so a publication that kept failing would otherwise
     * pile up a bundle's worth of secrets nobody can use every time it was tried. Sending the
     * same bundle again instead is no better: a publication whose response was lost may have
     * landed, and some of its one-time packages been handed out since, which would put them
     * back on offer. A try that was cancelled rather than failed does not count: the screen
     * that started it went away, and the next one to call this tries again.
     */
    suspend fun publishIfDue(): EmptyResult<KeyPackageError> {
        if (hasTriedDue || !isDue()) return Result.Success(Unit)

        hasTriedDue = true

        return try {
            publish()
        } catch (exception: CancellationException) {
            hasTriedDue = false

            throw exception
        }
    }

    private suspend fun isDue(): Boolean {
        val publishedAt = getKeyPackagesPublishedAt() ?: return true

        // Negative when the clock was set back since, which leaves no telling how old the
        // packages are, so they are replaced rather than trusted for however long it takes
        // the clock to catch up.
        val age = nowEpochMilliseconds() - publishedAt

        return age !in 0..<REPUBLISH_AFTER_MILLISECONDS
    }
}
