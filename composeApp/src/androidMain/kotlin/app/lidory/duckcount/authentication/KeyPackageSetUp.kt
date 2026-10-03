package app.lidory.duckcount.authentication

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import mls.KeyPackageRepository
import org.koin.compose.koinInject
import util.onError

private const val TAG = "KeyPackageSetUp"

/**
 * Publishes the user's key packages when they are due for replacing, or the login that
 * should have put them up could not, see [KeyPackageRepository.publishIfDue].
 *
 * Runs whenever the dashboard is shown, which right after a failed login is the next chance,
 * though the repository tries only once per launch. Costs one read of the vault when nothing
 * is due.
 */
@Composable
fun KeyPackageSetUp() {
    val keyPackageRepository = koinInject<KeyPackageRepository>()

    LaunchedEffect(Unit) {
        keyPackageRepository.publishIfDue().onError { error ->
            Log.w(TAG, "could not publish the key packages: $error")
        }
    }
}
