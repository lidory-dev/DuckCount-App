package app.lidory.duckcount

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import kotlin.system.exitProcess

/**
 * Set on the relaunch [restartApp] starts when there is something to tell the user, so the new
 * process can say why they are back at the start, see [RestartNotice].
 */
const val EXTRA_RESTART_NOTICE = "app.lidory.duckcount.extra.RESTART_NOTICE"

/** What the relaunched app tells the user about why they are back at the start. */
enum class RestartNotice {

    /** The server ended the session. */
    SESSION_EXPIRED,

    /**
     * The user deleted their account: the deletion said so, or the server ended the session after
     * one whose answer never came back, see `authenticationUtils.SessionEnd.ACCOUNT_DELETED`.
     */
    ACCOUNT_DELETED,
}

@StringRes
fun RestartNotice.messageRes(): Int = when (this) {
    RestartNotice.SESSION_EXPIRED -> R.string.session_expired
    RestartNotice.ACCOUNT_DELETED -> R.string.account_deleted
}

/**
 * Ends this process and starts the app again in a fresh one, on a task of its own.
 *
 * Every session ends here, whether the user logged out, deleted their account, or the server
 * ended it for them. `DuckCountClient.endSession` drops the key the local database is sealed
 * with, but this process opened the database with that key and would keep writing under it:
 * whatever the next session stored would be discarded, file and all, at the next launch, which
 * finds no key to open it with. Most of it the server can refill. The MLS state cannot, since
 * its secrets never leave the device. A new process finds no key before anybody signs in, so
 * the next session starts on a database of its own.
 *
 * [notice] is what the relaunched app tells the user, if anything: a log-out needs no telling.
 */
fun Context.restartApp(notice: RestartNotice? = null): Nothing {
    val launchIntent = requireNotNull(packageManager.getLaunchIntentForPackage(packageName))

    startActivity(
        Intent.makeRestartActivityTask(launchIntent.component)
            .putExtra(EXTRA_RESTART_NOTICE, notice?.name),
    )

    exitProcess(0)
}

/**
 * The notice the relaunch [restartApp] started carries, or null when there is none.
 *
 * Anything else reads as none: the launcher activity is exported, so whatever started it may
 * have set the extra to anything at all.
 */
fun Intent.restartNotice(): RestartNotice? {
    val name = getStringExtra(EXTRA_RESTART_NOTICE) ?: return null

    return RestartNotice.entries.firstOrNull { it.name == name }
}
