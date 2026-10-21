package app.lidory.duckcount.android.ui.composable.checkbox

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import app.lidory.duckcount.android.ui.R
import app.lidory.duckcount.android.ui.constant.Size

@Composable
fun DuckCountCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: CheckboxColors = CheckboxDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    DuckCountTriStateCheckbox(
        state = ToggleableState(checked),
        onClick = onCheckedChange?.let { { it(!checked) } },
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
    )
}

@Composable
fun DuckCountTriStateCheckbox(
    state: ToggleableState,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: CheckboxColors = CheckboxDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    IconButton(
        modifier = modifier.size(Size.small),
        enabled = enabled,
        colors = IconButtonColors(
            containerColor = colors.uncheckedBoxColor,
            contentColor = colors.checkedBoxColor,
            disabledContainerColor = colors.disabledUncheckedBoxColor,
            disabledContentColor = colors.disabledBorderColor,
        ),
        interactionSource = interactionSource,
        onClick = { onClick?.invoke() },
    ) {
        // The icons are tinted explicitly, so they would not dim with the button on their own.
        val tint = if (enabled) colors.checkedBoxColor else colors.disabledBorderColor

        when (state) {
            ToggleableState.On ->
                Icon(
                    tint = tint,
                    painter = painterResource(R.drawable.ic_checked_circle),
                    contentDescription = stringResource(R.string.checked),
                )

            ToggleableState.Off ->
                Icon(
                    tint = tint,
                    painter = painterResource(R.drawable.ic_empty_circle),
                    contentDescription = stringResource(R.string.unchecked),
                )

            ToggleableState.Indeterminate ->
                Icon(
                    tint = tint,
                    painter = painterResource(R.drawable.ic_dash_circle),
                    contentDescription = stringResource(R.string.indeterminate_checked),
                )
        }
    }
}
