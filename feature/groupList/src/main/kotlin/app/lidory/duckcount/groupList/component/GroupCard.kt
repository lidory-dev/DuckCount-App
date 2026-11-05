package app.lidory.duckcount.groupList.component

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.R
import app.lidory.duckcount.android.ui.R as androidUiR

/**
 * The group [groupUuid], marked when [isLeft], the user no longer being in it, removed or left, or
 * because its owner deleted it, [isDeleted].
 */
@Composable
fun GroupCard(
    title: String,
    description: String,
    isLeft: Boolean,
    isDeleted: Boolean,
    modifier: Modifier = Modifier,
    groupUuid: String,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    ElevatedCard(
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(all = Padding.large),
            horizontalArrangement = Arrangement.spacedBy(Padding.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
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

            if (isLeft) {
                Icon(
                    painter = painterResource(if (isDeleted) androidUiR.drawable.ic_delete else R.drawable.ic_person_off),
                    contentDescription = stringResource(
                        if (isDeleted) R.string.deleted_group_marker else R.string.left_group_marker,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
