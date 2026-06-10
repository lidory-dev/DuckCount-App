package app.lidory.duckcount.groupList.component

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.lidory.duckcount.android.ui.constant.Padding

@Composable
fun GroupCard(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    groupUuid: String,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    ElevatedCard(
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(all = Padding.large),
            verticalArrangement = Arrangement.Center,
        ) {
            with(sharedTransitionScope) {
                Text(
                    modifier = Modifier.sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "group_title_$groupUuid"),
                        animatedVisibilityScope = animatedVisibilityScope,
                    ),
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge,
                )

                if (description.isNotEmpty()) {
                    Text(
                        modifier = Modifier.sharedBounds(
                            sharedContentState = rememberSharedContentState(key = "group_description_$groupUuid"),
                            animatedVisibilityScope = animatedVisibilityScope,
                        ),
                        text = description,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}
