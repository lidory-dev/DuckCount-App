import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.android.ui.composable.NoticeScreen
import app.lidory.duckcount.authentication.R
import app.lidory.duckcount.android.ui.R as androidUiR

/**
 * Where a registration ends once the server has refused the phone's attestation. Nothing on the
 * form would change that answer, so the form is gone by now and [navigateBack] leads to the
 * onboarding screen, which still offers the login, as logging in takes no attestation.
 */
@Composable
fun DeviceNotAllowedScreen(navigateBack: () -> Unit) {
    NoticeScreen(
        title = stringResource(R.string.device_not_allowed_title),
        message = stringResource(R.string.error_device_not_allowed),
        buttonText = stringResource(androidUiR.string.back),
        onButtonClick = navigateBack,
    )
}
