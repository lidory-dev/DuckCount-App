package app.lidory.duckcount.push

import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import authenticationUtils.getAuthenticatedUserUuid
import kotlinx.coroutines.CancellationException
import networking.UpdateRequirement
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import push.PushRepository
import push.WEB_PUSH_TRANSPORT

private const val TAG = "PushRegistrationWorker"

/**
 * Tells the server where to wake this device, or that it no longer can.
 *
 * Carried by a worker rather than sent where it is learned, because the endpoint
 * arrives from the distributor whenever the distributor feels like it, often with no
 * network to hand: a registration that fell in the gap would leave the device
 * unreachable until the next login.
 *
 * Which of the two it does is decided by whether it was given an endpoint.
 */
class PushRegistrationWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters), KoinComponent {
    private val pushRepository: PushRepository by inject()

    private val updateRequirement: UpdateRequirement by inject()

    override suspend fun doWork(): Result {
        // The session is sealed by Keystore entries that require an unlocked device, so
        // a locked one cannot even tell whether it is signed in. A distributor hands out
        // endpoints whenever it likes, a locked screen included, so this waits.
        if (isDeviceLocked()) return waitForUnlock()

        return try {
            register()
        } catch (exception: CancellationException) {
            // WorkManager stopping the worker, see [PushSyncWorker.doWork].
            throw exception
        } catch (exception: IllegalStateException) {
            // The device locked while this was running, so the same applies.
            Log.w(TAG, "doWork: could not read this device's storage", exception)

            waitForUnlock()
        }
    }

    /**
     * Waits for the device to be unlocked, however long that takes, with the same request.
     *
     * Not counted against [MAX_ATTEMPTS], for the reason the sync gives, see
     * [PushSyncWorker]: a locked device is not a failure but the normal state of a phone in
     * a pocket, and counting it gave up about a quarter of an hour after the screen went
     * off. Here giving up leaves the server with an endpoint the distributor has replaced
     * or dropped, so the device hears nothing until the next login.
     */
    private fun waitForUnlock(): Result {
        PushWork.enqueueRegistrationAfterUnlockWait(context = applicationContext, inputData = inputData)

        return Result.success()
    }

    private suspend fun register(): Result {
        // Both calls need a session; without one there is nothing to register against,
        // and the next login registers from scratch anyway.
        if (getAuthenticatedUserUuid() == null) return Result.success()

        val outcome = when (val endpoint = inputData.getString(PushWork.ENDPOINT)) {
            null -> pushRepository.unregister()

            else -> pushRepository.register(
                transport = WEB_PUSH_TRANSPORT,
                endpoint = endpoint,
                p256dh = inputData.getString(PushWork.P256DH),
                auth = inputData.getString(PushWork.AUTH),
            )
        }

        if (outcome is util.Result.Error) {
            Log.w(TAG, "doWork: could not reach the server: ${outcome.error}")

            return retryOrGiveUp()
        }

        return Result.success()
    }

    /**
     * A build the server has refused is given up on at once, see [UpdateRequirement]: only an
     * update fixes that, and the first launch after it registers again, see [PushSetUp].
     */
    private fun retryOrGiveUp(): Result =
        if (!updateRequirement.isRequired.value && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()

    private fun isDeviceLocked(): Boolean =
        applicationContext.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
}

/**
 * How many times a failed call is retried. A locked device is not a failed call, see
 * [PushRegistrationWorker.waitForUnlock].
 *
 * More than the sync gets: nothing else will do this, whereas a missed sync is picked
 * up by the next screen that opens.
 */
private const val MAX_ATTEMPTS = 5
