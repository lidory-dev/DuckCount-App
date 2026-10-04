package app.lidory.duckcount

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
        // by a rotation for instance, carries the same intent and has already said it.
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_SESSION_EXPIRED, false)) {
            Toast.makeText(this, R.string.session_expired, Toast.LENGTH_LONG).show()
        }

        // Likewise only on the launch the notification started: a recreated activity gets
        // its back stack restored, request list included, and would push it a second time.
        val openContactRequests = savedInstanceState == null &&
            intent.getBooleanExtra(EXTRA_OPEN_CONTACT_REQUESTS, false)

        setContent {
            App(openContactRequests = openContactRequests)
        }
    }
}

/** How long the splash stays up at minimum, so the sign-off is legible. */
private const val SplashMinimumDurationMs = 1_000L
