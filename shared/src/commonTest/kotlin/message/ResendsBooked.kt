package message

/** Books nothing, keeping in how many seconds each resend was asked for, in order. */
internal class ResendsBooked : ResendScheduler {
    val seconds = mutableListOf<Long>()

    override suspend fun resendAfter(seconds: Long) {
        this.seconds += seconds
    }
}
