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
        enqueueSync(context = context, existingWork = ExistingWorkPolicy.KEEP)
    }

    /**
     * Looks again shortly, for a sync that found the device locked and could read nothing.
     *
     * A fresh request rather than [androidx.work.ListenableWorker.Result.retry], because a
     * retry carries the attempt count with it and WorkManager's backoff doubles on every
     * one: a phone that stayed locked a few minutes had worked its way up to intervals of
     * minutes, and that interval is what the user waits *after* picking the phone up,
     * looking at a notification that cannot yet say who the expense is from.
     *
     * There is no event to wait on instead. `ACTION_USER_PRESENT` cannot be declared in the
     * manifest, and a receiver registered at runtime never arrives: between two checks the
     * app is a cached process, and Android defers broadcasts to cached processes. Measured
     * on a Pixel 7 Pro on Android 16, exported and not, with the process alive and
     * unfrozen. So this looks, on a fixed interval, and the wait after an unlock is that
     * interval rather than however long the phone had been in a pocket.
     *
     * Cheap, because a look at a locked device ends at the first line of
     * [PushSyncWorker.doWork], and because a phone that is actually away dozes: the system
     * batches these into its maintenance windows instead of running them on the minute.
     */
    fun enqueueSyncAfterUnlockWait(context: Context) {
        val request = OneTimeWorkRequestBuilder<PushSyncWorker>()
            .setInitialDelay(UNLOCK_WAIT_SECONDS, TimeUnit.SECONDS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            SYNC_WORK,
            // Replacing the one that is finishing as it asks for this.
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun enqueueSync(context: Context, existingWork: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<PushSyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            SYNC_WORK,
            existingWork,
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

/**
 * How long a sync waits before looking again at a device that was locked.
 *
 * Fixed, and the whole of what a user waits after unlocking. Short enough that the named
 * notification arrives while they are still looking at the nameless one, long enough that a
 * phone left alone is not woken on the half-minute.
 */
private const val UNLOCK_WAIT_SECONDS = 20L
