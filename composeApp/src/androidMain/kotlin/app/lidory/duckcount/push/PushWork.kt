package app.lidory.duckcount.push

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * The work a push signal starts.
 *
 * Every callback of [DuckCountPushService] ends here rather than doing the work
 * itself: the process the distributor woke may be gone moments later, and WorkManager
 * is what carries the work past that and retries it when the network was the problem.
 */
object PushWork {
    private const val SYNC_WORK = "push-sync"

    private const val REGISTRATION_WORK = "push-registration"

    internal const val ENDPOINT = "endpoint"

    internal const val P256DH = "p256dh"

    internal const val AUTH = "auth"

    /**
     * Syncs what the signal was about.
     *
     * Expedited, since the point of a push is to be prompt, and falling back to
     * ordinary work when the app has spent its quota. Unique and keeping the running
     * one: two signals arriving together are one thing to go and read.
     */
    fun enqueueSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<PushSyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            SYNC_WORK,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Tells the server where to wake this device.
     *
     * Not expedited: nothing is waiting on it, and it is worth nothing without a
     * network. Replaces any registration still queued, since only the newest endpoint
     * is worth sending.
     */
    fun enqueueRegistration(context: Context, endpoint: String, p256dh: String, auth: String) {
        val request = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
            .setInputData(
                workDataOf(ENDPOINT to endpoint, P256DH to p256dh, AUTH to auth),
            )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            REGISTRATION_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /** Tells the server to forget this device, when the distributor dropped it. */
    fun enqueueUnregistration(context: Context) {
        val request = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            REGISTRATION_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

/** How long WorkManager waits before the first retry, doubling from there. */
private const val BACKOFF_SECONDS = 30L
