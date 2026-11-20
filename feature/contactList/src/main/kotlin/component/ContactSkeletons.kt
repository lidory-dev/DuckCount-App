package component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.android.ui.composable.skeleton.AvatarBone
import app.lidory.duckcount.android.ui.composable.skeleton.Bone
import app.lidory.duckcount.android.ui.composable.skeleton.FieldBone
import app.lidory.duckcount.android.ui.composable.skeleton.Skeleton
import app.lidory.duckcount.android.ui.composable.skeleton.SkeletonCard
import app.lidory.duckcount.android.ui.composable.skeleton.SkeletonPersonCard
import app.lidory.duckcount.android.ui.composable.skeleton.TextBone
import app.lidory.duckcount.android.ui.composable.skeleton.skeletonLineWidth
import app.lidory.duckcount.android.ui.constant.Padding

/**
 * The contacts while they are read: the search field, then the contacts' cards under the letters
 * of their sections.
 */
@Composable
fun ContactListSkeleton(modifier: Modifier = Modifier) {
    Skeleton(
        modifier = modifier.padding(horizontal = Padding.medium),
        verticalArrangement = Arrangement.spacedBy(Padding.large),
    ) {
        FieldBone()

        Column {
            var line = 0

            CONTACTS_PER_SECTION.forEach { contactCount ->
                TextBone(
                    modifier = Modifier
                        .padding(horizontal = Padding.medium)
                        .width(12.dp),
                )

                repeat(contactCount) {
                    SkeletonPersonCard(
                        modifier = Modifier.padding(vertical = Padding.medium),
                        nameWidth = skeletonLineWidth(line++),
                    )
                }
            }
        }
    }
}

/** The blocked users while they are read: what blocking does, then their cards. */
@Composable
fun BlockedUserListSkeleton(modifier: Modifier = Modifier) {
    Skeleton(
        modifier = modifier.padding(horizontal = Padding.medium),
        verticalArrangement = Arrangement.spacedBy(Padding.medium),
    ) {
        Column(modifier = Modifier.padding(horizontal = Padding.medium)) {
            TextBone(style = MaterialTheme.typography.bodyMedium)

            TextBone(
                modifier = Modifier.fillMaxWidth(0.6f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        repeat(BLOCKED_USER_COUNT) { index ->
            SkeletonPersonCard(nameWidth = skeletonLineWidth(index)) {
                // The unblock button.
                TextBone(
                    modifier = Modifier
                        .padding(horizontal = Padding.large)
                        .padding(end = Padding.medium)
                        .width(64.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * The contact requests while they are read: [ContactRequestCard]s, the sender and the three
 * answers to come.
 */
@Composable
fun ContactRequestListSkeleton(modifier: Modifier = Modifier) {
    Skeleton(
        modifier = modifier.padding(horizontal = Padding.medium),
        verticalArrangement = Arrangement.spacedBy(Padding.medium),
    ) {
        repeat(REQUEST_COUNT) { index ->
            SkeletonCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Padding.large, start = Padding.large, end = Padding.large),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(TextFieldDefaults.MinHeight),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AvatarBone()

                        TextBone(modifier = Modifier.fillMaxWidth(skeletonLineWidth(index)))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Padding.medium),
                    ) {
                        repeat(3) {
                            Bone(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(ButtonDefaults.MinHeight),
                                shape = RoundedCornerShape(Padding.large),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A contact's safety number while it is worked out: their name, the number's twelve groups of
 * digits as [SafetyNumberCard] lays them out, and what comparing them means.
 */
@Composable
fun ContactVerificationSkeleton(modifier: Modifier = Modifier) {
    Skeleton(
        modifier = modifier.padding(horizontal = Padding.medium, vertical = Padding.large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Padding.large),
    ) {
        TextBone(
            modifier = Modifier.fillMaxWidth(0.5f),
            style = MaterialTheme.typography.headlineSmall,
        )

        val groupStyle = MaterialTheme.typography.titleLarge
        // Five monospaced digits, each about 0.6 of the font's size wide.
        val groupWidth = with(LocalDensity.current) { (groupStyle.fontSize * 3).toDp() }

        SkeletonCard(modifier = Modifier.fillMaxWidth()) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Padding.extraLarge),
                horizontalArrangement = Arrangement.spacedBy(Padding.large, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(Padding.large),
                maxItemsInEachRow = 4,
            ) {
                repeat(SAFETY_NUMBER_GROUPS) {
                    TextBone(modifier = Modifier.width(groupWidth), style = groupStyle)
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            listOf(0.9f, 0.85f, 0.5f).forEach { width ->
                TextBone(modifier = Modifier.fillMaxWidth(width))
            }
        }
    }
}

/** How many contacts each section of [ContactListSkeleton] has, more than a tall screen shows. */
private val CONTACTS_PER_SECTION = listOf(2, 3, 1, 2, 3)

/** How many blocked users [BlockedUserListSkeleton] lists. */
private const val BLOCKED_USER_COUNT = 4

/** How many requests [ContactRequestListSkeleton] lists. */
private const val REQUEST_COUNT = 4

/** How many groups of digits a safety number has. */
private const val SAFETY_NUMBER_GROUPS = 12
