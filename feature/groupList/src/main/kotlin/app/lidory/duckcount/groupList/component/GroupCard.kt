package app.lidory.duckcount.groupList.component

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.R

/**
 * How much of the content of the card of a group the user is out of shows through: dimmed, but less
 * than something disabled would be, the card still opening the group, its title kept above 3:1 on
 * the gray.
 */
private const val LEFT_GROUP_CONTENT_ALPHA = 0.6f

/**
 * The group [groupUuid], grayed out and crossed out when [isLeft], the user no longer being in it,
 * removed, left, or its owner having deleted it, [isDeleted]: which of them, screen readers are told.
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
    val leftMarker = stringResource(if (isDeleted) R.string.deleted_group_marker else R.string.left_group_marker)
    val textDecoration = if (isLeft) TextDecoration.LineThrough else null

    ElevatedCard(
        modifier = if (isLeft) modifier.semantics { stateDescription = leftMarker } else modifier,
        colors = if (isLeft) leftGroupCardColors() else CardDefaults.elevatedCardColors(),
        elevation = if (isLeft) {
            CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
        } else {
            CardDefaults.elevatedCardElevation()
        },
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
                    textDecoration = textDecoration,
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
                        textDecoration = textDecoration,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun leftGroupCardColors(): CardColors = CardDefaults.elevatedCardColors(
    containerColor = MaterialTheme.colorScheme.surfaceVariant,
    contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = LEFT_GROUP_CONTENT_ALPHA),
)
