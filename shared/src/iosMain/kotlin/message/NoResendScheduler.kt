package message

/**
 * Books nothing: nothing on iOS sends a direct message yet, so no limit ever leaves one unsent.
 * The seam the passes resend through once it does, see [ResendScheduler].
 */
internal object NoResendScheduler : ResendScheduler {
    override suspend fun resendAfter(seconds: Long) = Unit
}
