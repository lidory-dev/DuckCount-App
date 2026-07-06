package app.lidory.duckcount.android.ui.composable.button

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.android.ui.R
import app.lidory.duckcount.android.ui.constant.Padding

@Composable
fun PasswordVisibilityButton(
    modifier: Modifier = Modifier,
    isPasswordVisible: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        modifier = modifier.padding(horizontal = Padding.medium),
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
