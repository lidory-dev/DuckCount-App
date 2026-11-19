package sync

/**
 * Has iOS run a sync while the app is not running, which is where Android's WorkManager runs the
 * work this would otherwise wait on, implemented by the app with a background refresh task.
 *
 * Best effort: iOS runs one no earlier than asked, and whenever it sees fit after that, seldom for
 * an app the user rarely opens and never for one they swiped away. What it misses waits on the
 * server, or in what the device saved before sending it, for the next sync that runs.
 */
interface CatchUpScheduler {

    /**
     * Asks for a catch-up no sooner than [seconds] from now, unless one is asked for sooner already.
     */
    fun catchUpAfter(seconds: Long)
}
