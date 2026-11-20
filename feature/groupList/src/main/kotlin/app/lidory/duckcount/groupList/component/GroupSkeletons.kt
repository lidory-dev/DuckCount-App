package app.lidory.duckcount.groupList.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.android.ui.composable.skeleton.Bone
import app.lidory.duckcount.android.ui.composable.skeleton.FieldBone
import app.lidory.duckcount.android.ui.composable.skeleton.Skeleton
import app.lidory.duckcount.android.ui.composable.skeleton.SkeletonCard
import app.lidory.duckcount.android.ui.composable.skeleton.TextBone
import app.lidory.duckcount.android.ui.composable.skeleton.skeletonLineWidth
import app.lidory.duckcount.android.ui.constant.Padding
import kotlin.math.abs

/** The groups while they are read: [GroupCard]s, their title and description to come. */
@Composable
fun GroupListSkeleton(modifier: Modifier = Modifier) {
    Skeleton(
        modifier = modifier.padding(horizontal = Padding.medium),
        verticalArrangement = Arrangement.spacedBy(Padding.medium),
    ) {
        // The list's own space above its first card.
        Spacer(modifier = Modifier.height(Padding.small * 2))

        repeat(GROUP_COUNT) { index ->
            SkeletonCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(all = Padding.large)) {
                    TextBone(
                        modifier = Modifier.fillMaxWidth(skeletonLineWidth(index)),
                        style = MaterialTheme.typography.titleLarge,
                    )

                    TextBone(
                        modifier = Modifier.fillMaxWidth(skeletonLineWidth(index + 3)),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

/**
 * A group's balances while they are worked out: [BalanceGraphItem]'s rows, each member's name on
 * one side of the middle and their bar on the other, those owed first, growing right, then those
 * who owe, growing left.
 */
@Composable
fun GroupBalanceSkeleton(modifier: Modifier = Modifier) {
    Skeleton(modifier = modifier.padding(top = Padding.medium)) {
        BALANCE_BAR_WIDTHS.forEachIndexed { index, barWidth ->
            val isOwed = barWidth > 0

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Padding.small, start = Padding.small, end = Padding.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val name: @Composable () -> Unit = {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = Padding.medium),
                        contentAlignment = if (isOwed) Alignment.CenterEnd else Alignment.CenterStart,
                    ) {
                        TextBone(modifier = Modifier.fillMaxWidth(skeletonLineWidth(index)))
                    }
                }

                val bar: @Composable () -> Unit = {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = if (isOwed) Alignment.CenterStart else Alignment.CenterEnd,
                    ) {
                        Bone(
                            modifier = Modifier
                                .fillMaxWidth(abs(barWidth))
                                .height(40.dp),
                            shape = if (isOwed) {
                                RoundedCornerShape(topEnd = Padding.medium, bottomEnd = Padding.medium)
                            } else {
                                RoundedCornerShape(topStart = Padding.medium, bottomStart = Padding.medium)
                            },
                        )
                    }
                }

                if (isOwed) {
                    name()
                    bar()
                } else {
                    bar()
                    name()
                }
            }
        }
    }
}

/** A group's settling up while it is worked out: the debts' cards, as [DebtCard] draws them. */
@Composable
fun GroupSettleUpSkeleton(modifier: Modifier = Modifier) {
    Skeleton(modifier = modifier.padding(Padding.medium)) {
        repeat(DEBT_COUNT) { index ->
            SkeletonCard(
                modifier = Modifier
                    .padding(Padding.medium)
                    .fillMaxWidth()
                    .heightIn(min = LocalMinimumInteractiveComponentSize.current),
            ) {
                TextBone(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(all = Padding.medium)
                        .fillMaxWidth(skeletonLineWidth(index + 2)),
                )
            }
        }
    }
}

/**
 * A group's history while it is read: [GroupVersionCard]s, each version's title, description and
 * when it was made, spaced as the history spaces them.
 */
@Composable
fun GroupHistorySkeleton(modifier: Modifier = Modifier) {
    Skeleton(
        modifier = modifier.padding(horizontal = Padding.medium),
        verticalArrangement = Arrangement.spacedBy(Padding.large),
    ) {
        repeat(VERSION_COUNT) { index ->
            SkeletonCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(Padding.large)) {
                    TextBone(
                        modifier = Modifier.fillMaxWidth(skeletonLineWidth(index)),
                        style = MaterialTheme.typography.titleMedium,
                    )

                    Spacer(modifier = Modifier.height(Padding.small))

                    TextBone(
                        modifier = Modifier.fillMaxWidth(skeletonLineWidth(index + 4)),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    Spacer(modifier = Modifier.height(Padding.medium))

                    TextBone(
                        modifier = Modifier.fillMaxWidth(0.4f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/**
 * The group form while the group it edits is read: its title, the currency a new group picks,
 * and its description, as empty fields.
 */
@Composable
fun GroupFormSkeleton(
    isCreation: Boolean,
    modifier: Modifier = Modifier,
) {
    Skeleton(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Padding.medium),
    ) {
        FieldBone()

        if (isCreation) {
            FieldBone()
        }

        FieldBone()
    }
}

/** How many groups [GroupListSkeleton] lists, more than a tall screen shows. */
private const val GROUP_COUNT = 10

/**
 * The bars of [GroupBalanceSkeleton], as fractions of half the row: positive for a member owed,
 * negative for one who owes, in the order the balances sort.
 */
private val BALANCE_BAR_WIDTHS = listOf(0.9f, 0.55f, 0.2f, -0.3f, -0.6f, -0.75f)

/** How many debts [GroupSettleUpSkeleton] lists. */
private const val DEBT_COUNT = 4

/** How many versions [GroupHistorySkeleton] lists, more than a tall screen shows. */
private const val VERSION_COUNT = 8
