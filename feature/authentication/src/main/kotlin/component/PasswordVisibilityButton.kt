package component

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.authentication.R

@Composable
fun PasswordVisibilityButton(onClick: () -> Unit, isPasswordVisible: Boolean) {
    IconButton(
        onClick = onClick,
    ) {
        Icon(
            painter = painterResource(
                if (isPasswordVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
            ),
            contentDescription = if (isPasswordVisible) stringResource(R.string.hide) else stringResource(
                R.string.show,
            ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}