package app.lidory.duckcount

import android.content.Context
import android.content.Intent
import kotlin.system.exitProcess

/**
 * Set on the relaunch that follows a session the server ended, so the new process can tell
 * the user why they are back at the start.
 */
const val EXTRA_SESSION_EXPIRED = "app.lidory.duckcount.extra.SESSION_EXPIRED"

/**
 * Ends this process and starts the app again in a fresh one, on a task of its own.
 *
 * Every session ends here, whether the user logged out or the server ended it for them.
 * `DuckCountClient.endSession` drops the key the local database is sealed with, but this
 * process opened the database with that key and would keep writing under it: whatever the
 * next session stored would be discarded, file and all, at the next launch, which finds no
 * key to open it with. Most of it the server can refill. The MLS state cannot, since its
 * secrets never leave the device. A new process finds no key before anybody signs in, so
 * the next session starts on a database of its own.
 *
 * [sessionExpired] says the server ended the session, which the relaunched app then tells
 * the user.
 */
fun Context.restartApp(sessionExpired: Boolean = false) {
    val launchIntent = requireNotNull(packageManager.getLaunchIntentForPackage(packageName))

    startActivity(
        Intent.makeRestartActivityTask(launchIntent.component)
            .putExtra(EXTRA_SESSION_EXPIRED, sessionExpired),
    )

    exitProcess(0)
}
