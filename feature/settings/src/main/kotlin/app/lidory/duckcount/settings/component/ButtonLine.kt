package app.lidory.duckcount.settings.component

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.android.ui.constant.Padding

@Composable
fun ButtonLine(
    modifier: Modifier = Modifier,
    @StringRes text: Int,
    @DrawableRes icon: Int? = null,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(Padding.large),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Padding.large),
        ) {
            icon?.let {
                Icon(
                    painter = painterResource(it),
                    contentDescription = stringResource(text),
                )
            }

            Text(text = stringResource(text))
        }
    }
}