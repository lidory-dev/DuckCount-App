package sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import message.ResendScheduler
import networking.UpdateRequirement
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Runs a [ForegroundSync] again once a pass asked for it, which iOS has no WorkManager for: a
 * creation or a change of a group's members the server has not answered, a leave it did not take, a
 * departed member's grace running out, a limit of the server's lifting, each of which Android books
 * as a resend, see [ResendScheduler].
 *
 * Kept for the soonest moment asked for, while the app runs: iOS suspends it soon after it leaves
 * the foreground, and the timer goes on from where it was once it comes back, by when the screens
 * run a sync of their own anyway. Nothing is lost if the app is killed meanwhile: whatever waited
 * is saved, and the next sync sends it.
 *
 * A pass this runs that fails is run again, 30 seconds later and twice as late each time, three
 * times at most, as Android's push worker retries the sync a resend runs: what a failure left unsent
 * books nothing itself, but for the server's limit, so a resend that fell on a moment offline would
 * otherwise wait for the next time a screen syncs.
 *
 * The sync is launched, never run where it is asked for: a booking is made while a group sync holds
 * the groups' lock, which the sync it books would wait for.
 */
class ForegroundResendScheduler(
    private val foregroundSync: Lazy<ForegroundSync>,
    private val updateRequirement: UpdateRequirement,
) : ResendScheduler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Read and written on the main thread only.
    private var booked: Job? = null
    private var dueAt: TimeSource.Monotonic.ValueTimeMark? = null
    private var retries = 0

    override suspend fun resendAfter(seconds: Long) {
        withContext(Dispatchers.Main) { book(seconds = seconds) }
    }

    /** Books a pass in [seconds], unless one is booked sooner. On the main thread. */
    private fun book(seconds: Long) {
        // An outdated app may send nothing: the server answers it nothing but its refusal.
        if (updateRequirement.isRequired.value) return

        val delay = seconds.coerceAtLeast(0).seconds
        val due = TimeSource.Monotonic.markNow() + delay
        val earlier = dueAt

        if (booked?.isActive == true && earlier != null && earlier <= due) return

        booked?.cancel()
        dueAt = due
        booked = scope.launch {
            delay(delay)

            // Due: whatever is booked from here on is a new booking, which must not cancel this pass.
            dueAt = null
            booked = null

            val pass = foregroundSync.value.sync()

            if (pass.hasFailed && retries < MAX_RETRIES) {
                book(seconds = FIRST_RETRY_SECONDS shl retries)
                retries += 1
            } else if (!pass.hasFailed) {
                retries = 0
            }
        }
    }

    private companion object {
        /** As Android's push worker: three attempts after the first. */
        const val MAX_RETRIES = 3

        const val FIRST_RETRY_SECONDS = 30L
    }
}
