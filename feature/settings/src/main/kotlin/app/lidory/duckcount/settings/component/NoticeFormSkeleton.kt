package app.lidory.duckcount.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.android.ui.composable.skeleton.Bone
import app.lidory.duckcount.android.ui.composable.skeleton.FieldBone
import app.lidory.duckcount.android.ui.composable.skeleton.Skeleton
import app.lidory.duckcount.android.ui.composable.skeleton.SkeletonCard
import app.lidory.duckcount.android.ui.composable.skeleton.TextBone
import app.lidory.duckcount.android.ui.constant.Padding

/**
 * A form of one field under a card saying what it is for, the profile's and the username's, while
 * what the field holds is read: the card's icon and text, then the field, empty.
 */
@Composable
fun NoticeFormSkeleton(modifier: Modifier = Modifier) {
    Skeleton(modifier = modifier.padding(horizontal = Padding.large)) {
        SkeletonCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Bone(
                    modifier = Modifier
                        .padding(top = Padding.large)
                        .size(32.dp),
                    shape = CircleShape,
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Padding.small, bottom = Padding.medium, start = Padding.medium, end = Padding.medium),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    listOf(0.9f, 0.95f, 0.55f).forEach { width ->
                        TextBone(modifier = Modifier.fillMaxWidth(width))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        FieldBone()
    }
}
