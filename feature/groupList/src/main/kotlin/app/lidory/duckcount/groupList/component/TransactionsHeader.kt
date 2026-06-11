package app.lidory.duckcount.groupList.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.R
import app.lidory.duckcount.android.ui.R as androidUiR

@Composable
fun TransactionsHeader(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(durationMillis = 300),
    )

    Row(
        modifier = Modifier
            .clickable(onClick = onToggle)
            .fillMaxWidth()
            .padding(Padding.medium),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title)

        IconButton(onClick = onToggle) {
            Icon(
                painter = painterResource(androidUiR.drawable.ic_arrow_drop_down),
                contentDescription = stringResource(R.string.expand),
                modifier = Modifier.rotate(rotationAngle),
            )
        }
    }
}