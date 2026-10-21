package message

/**
 * Sends again, once the server's limit on messages lets it, whatever a pass left unsent for it.
 *
 * A pass that hands out keys or asks for them sends one message per person, and a refusal for
 * the limit leaves what it did not send where it was: still owed, still due, see
 * [DirectSendError.RateLimited]. Nothing else brings the pass back soon. It runs again with the
 * next expense written, the next time the list is opened, or at a push this device receives,
 * which may all be hours away, and among what waits is the key a beneficiary cannot open the
 * expense without, whose message is what would have woken them. So every refusal books the
 * passes again for the moment its limit reopens: the limit delays a message, and drops none.
 *
 * Booked for the soonest moment asked for. One that comes too early costs a refusal, which books
 * it again for when that one reopens; one that comes too late holds back whatever could have
 * gone out meanwhile.
 *
 * Bound on Android, which runs the sync a push starts, see `PushWork.enqueueResend` in
 * `:composeApp`; a no-op on iOS, which sends no direct message yet.
 */
interface ResendScheduler {

    /**
     * Runs every pass that sends messages again in [seconds], unless they are booked to run
     * sooner already.
     */
    suspend fun resendAfter(seconds: Long)
}
