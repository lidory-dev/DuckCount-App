package app.lidory.duckcount.push

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import org.koin.compose.koinInject

/**
 * Gets this device ready to be woken, once the user is signed in.
 *
 * Runs on every launch of a signed-in app rather than only after signing in: an
 * endpoint can change on its own, a phone that had no distributor may have one now,
 * and re-registering the same endpoint costs nothing (the repository says nothing to
 * the server when nothing changed).
 *
 * The permission is asked for here too, which is the one place the app is in front of
 * the user *and* known to have something to notify them about.
 */
@Composable
fun PushSetUp() {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val registrar = koinInject<UnifiedPushRegistrar>()

    val permission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        // Nothing to do either way: a refusal leaves the sync working and silent, and
        // Android stops asking on its own after the second one.
        onResult = {},
    )

    LaunchedEffect(activity) {
        // Null only where there is no activity to speak of, a preview for instance.
        if (activity == null) return@LaunchedEffect

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        registrar.ensureRegistered(activity = activity)
    }
}
