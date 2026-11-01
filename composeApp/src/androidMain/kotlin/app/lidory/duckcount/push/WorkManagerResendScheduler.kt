package app.lidory.duckcount.push

import android.content.Context
import message.ResendScheduler
import networking.UpdateRequirement

/**
 * Books the resends the shared layer asks for as work, see [PushWork.enqueueResend].
 *
 * Nothing once the server has refused this build, see [UpdateRequirement]. A group creation it
 * will not take asks again every minute, each booking starting a sync that asks again, which would
 * outlive the app being closed for as long as it is not updated. What waits stays saved, and the
 * first sync after the update sends it.
 */
class WorkManagerResendScheduler(
    private val context: Context,
    private val updateRequirement: UpdateRequirement,
) : ResendScheduler {

    override suspend fun resendAfter(seconds: Long) {
        if (updateRequirement.isRequired.value) return

        PushWork.enqueueResend(context = context, seconds = seconds)
    }
}
