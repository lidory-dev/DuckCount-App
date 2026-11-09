package app.lidory.duckcount

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

/**
 * Set on the intent a contact-request notification opens, so the app lands on the list
 * of requests rather than wherever it was left.
 */
const val EXTRA_OPEN_CONTACT_REQUESTS = "app.lidory.duckcount.extra.OPEN_CONTACT_REQUESTS"

/**
 * Set on the intent a notification about a group the user was added to opens, or about an expense
 * of one of their groups, so the app lands on the group list, where the group is, rather than on
 * the one-time expenses.
 */
const val EXTRA_OPEN_GROUPS = "app.lidory.duckcount.extra.OPEN_GROUPS"

/**
 * Set next to [EXTRA_OPEN_GROUPS], to the UUID of the group the notification is about, so the app
 * lands on that group, over the group list.
 */
const val EXTRA_OPEN_GROUP_UUID = "app.lidory.duckcount.extra.OPEN_GROUP_UUID"

/**
 * Set to the UUID of the expense the notification is about, so the app lands on that expense, over
 * its group when [EXTRA_OPEN_GROUP_UUID] names one and over the one-time expenses otherwise.
 */
const val EXTRA_OPEN_EXPENSE_UUID = "app.lidory.duckcount.extra.OPEN_EXPENSE_UUID"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate. Backports the Android 12 splash to API 24+.
        // This is the only splash the app shows: the sign-off is part of it, via
        // windowSplashScreenBrandingImage, so it is on screen from the first frame.
        val splashScreen = installSplashScreen()

        // The launch destination is resolved from local storage in a few milliseconds, so
        // without a floor the splash would be gone before the sign-off could be read.
        val shownAt = SystemClock.uptimeMillis()
        splashScreen.setKeepOnScreenCondition {
            SystemClock.uptimeMillis() - shownAt < SplashMinimumDurationMs
        }

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Only on the launch the restart itself started: an activity recreated afterwards,
        // by a rotation for instance, carries the same intent and has already said it. Nor
        // on a launch from Recents, which hands a finished task its first intent again, the
        // restart's when the restart is what made the task.
        val isFromRecents = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0

        if (savedInstanceState == null && !isFromRecents) {
            intent.restartNotice()?.let { notice ->
                Toast.makeText(this, notice.messageRes(), Toast.LENGTH_LONG).show()
            }
        }

        // Likewise only on the launch the notification started: a recreated activity gets
        // its back stack restored, request list included, and would push it a second time.
        val openContactRequests = savedInstanceState == null &&
            intent.getBooleanExtra(EXTRA_OPEN_CONTACT_REQUESTS, false)
        val openGroups = savedInstanceState == null && intent.getBooleanExtra(EXTRA_OPEN_GROUPS, false)

        // Nor from Recents, which would open them again long after the notification did.
        val mayOpenWhatItNames = savedInstanceState == null && !isFromRecents
        val openGroupUuid = intent.getStringExtra(EXTRA_OPEN_GROUP_UUID).takeIf { mayOpenWhatItNames }
        val openExpenseUuid = intent.getStringExtra(EXTRA_OPEN_EXPENSE_UUID).takeIf { mayOpenWhatItNames }

        setContent {
            App(
                openContactRequests = openContactRequests,
                openGroups = openGroups,
                openGroupUuid = openGroupUuid,
                openExpenseUuid = openExpenseUuid,
            )
        }
    }
}

/** How long the splash stays up at minimum, so the sign-off is legible. */
private const val SplashMinimumDurationMs = 1_000L
