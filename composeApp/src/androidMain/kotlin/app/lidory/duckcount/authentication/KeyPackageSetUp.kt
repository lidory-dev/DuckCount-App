package app.lidory.duckcount.authentication

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import mls.KeyPackageRepository
import org.koin.compose.koinInject
import util.onError

private const val TAG = "KeyPackageSetUp"

/**
 * Publishes the user's key packages when the login that should have done it could not, see
 * [KeyPackageRepository.publishIfOwed].
 *
 * Runs whenever the dashboard is shown, which right after a failed login is the next chance,
 * though the repository tries only once per launch. Costs one read of the vault once they
 * are up.
 */
@Composable
fun KeyPackageSetUp() {
    val keyPackageRepository = koinInject<KeyPackageRepository>()

    LaunchedEffect(Unit) {
        keyPackageRepository.publishIfOwed().onError { error ->
            Log.w(TAG, "could not publish the key packages: $error")
        }
    }
}
