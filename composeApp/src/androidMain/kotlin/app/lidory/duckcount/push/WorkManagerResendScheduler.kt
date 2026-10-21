package app.lidory.duckcount.push

import android.content.Context
import message.ResendScheduler

/** Books the resends the shared layer asks for as work, see [PushWork.enqueueResend]. */
class WorkManagerResendScheduler(
    private val context: Context,
) : ResendScheduler {

    override suspend fun resendAfter(seconds: Long) = PushWork.enqueueResend(context = context, seconds = seconds)
}
