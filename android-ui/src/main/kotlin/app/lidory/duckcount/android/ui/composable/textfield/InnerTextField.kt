package app.lidory.duckcount.android.ui.composable.textfield

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.lidory.duckcount.android.ui.constant.Padding

@Composable
fun InnerTextField(
    labelName: String?,
    modifier: Modifier = Modifier,
    error: String? = "",
    isInError: Boolean = false,
    colors: TextFieldColors = TextFieldDefaults.colors(),
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .animateContentSize(
                animationSpec = spring(),
            ),
        verticalArrangement = Arrangement.spacedBy(
            Padding.small,
        ),
    ) {
        labelName?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = colors.unfocusedLabelColor,
            )
        }
        content()
        AnimatedVisibility(
            enter = slideInVertically(
                animationSpec = tween(),
                initialOffsetY = { it },
            ),
            exit = shrinkVertically(
                shrinkTowards = Alignment.Top,
            ),
            visible = isInError,
        ) {

            Text(
                text = error.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = TextFieldDefaults.colors().errorLabelColor,
            )
        }
    }
}