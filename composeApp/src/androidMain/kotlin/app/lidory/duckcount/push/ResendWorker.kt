package app.lidory.duckcount.push

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Starts the sync a push starts, once the server's limit on messages lets through again what it
 * refused, or a member who departed a group may be removed by this device, see
 * [PushWork.enqueueResend].
 *
 * Nothing more: the sync is a [PushSyncWorker], run in the push's own unique work, so it waits
 * for an unlock and a network the way a push's does, and never runs alongside one. This is the
 * booking, under a name of its own, which a sync can move without stopping itself.
 */
class ResendWorker(
    context: Context,
    parameters: WorkerParameters,
) : Worker(context, parameters) {

    override fun doWork(): Result {
        PushWork.enqueueSyncAfterResendWait(context = applicationContext)

        return Result.success()
    }
}
