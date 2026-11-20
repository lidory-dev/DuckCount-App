package app.lidory.duckcount.expenseList.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.android.ui.composable.skeleton.Skeleton
import app.lidory.duckcount.android.ui.composable.skeleton.SkeletonCard
import app.lidory.duckcount.android.ui.composable.skeleton.SkeletonPersonCard
import app.lidory.duckcount.android.ui.composable.skeleton.TextBone
import app.lidory.duckcount.android.ui.composable.skeleton.skeletonLineWidth
import app.lidory.duckcount.android.ui.constant.Padding

/**
 * An expense list while its first page is read: days under their raised date card, as
 * [StickyHeaderElevated] draws it, each with a few of [ExpenseCard]'s cards, laid out as the list
 * lays them out.
 */
@Composable
fun ExpenseListSkeleton(modifier: Modifier = Modifier) {
    Skeleton(
        modifier = modifier.padding(top = Padding.medium, start = Padding.medium, end = Padding.medium),
    ) {
        var line = 0

        EXPENSES_PER_DAY.forEachIndexed { day, expenseCount ->
            if (day > 0) {
                Spacer(modifier = Modifier.height(Padding.large))
            }

            DayHeaderBone(modifier = Modifier.padding(bottom = Padding.medium))

            repeat(expenseCount) {
                ExpenseCardBone(
                    modifier = Modifier.padding(horizontal = Padding.medium, vertical = Padding.small),
                    index = line++,
                )
            }
        }
    }
}

/** [StickyHeaderElevated], its date to come. */
@Composable
private fun DayHeaderBone(modifier: Modifier = Modifier) {
    SkeletonCard(
        modifier = modifier
            .padding(all = Padding.small)
            .fillMaxWidth(),
    ) {
        TextBone(
            modifier = Modifier
                .padding(all = Padding.medium)
                .fillMaxWidth(0.35f),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/** [ExpenseCard], its title, who paid and the amounts to come. */
@Composable
private fun ExpenseCardBone(index: Int, modifier: Modifier = Modifier) {
    SkeletonCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(all = Padding.large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(all = Padding.small),
            ) {
                TextBone(
                    modifier = Modifier.fillMaxWidth(skeletonLineWidth(index)),
                    style = MaterialTheme.typography.titleLarge,
                )

                TextBone(
                    modifier = Modifier.fillMaxWidth(0.45f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                TextBone(
                    modifier = Modifier.width(72.dp),
                    style = MaterialTheme.typography.titleLarge,
                )

                TextBone(
                    modifier = Modifier.width(48.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

/**
 * An expense, or one of its versions, while it is read: its title and description, who paid and
 * who shares in it, as [UserAmountCard]s under their headings. [showsAuthor] adds the line a
 * version opens with, who made it.
 */
@Composable
fun ExpenseDetailSkeleton(
    modifier: Modifier = Modifier,
    showsAuthor: Boolean = false,
) {
    Skeleton(
        modifier = modifier.padding(horizontal = Padding.medium),
        verticalArrangement = Arrangement.spacedBy(Padding.small),
    ) {
        if (showsAuthor) {
            Column {
                TextBone(modifier = Modifier.fillMaxWidth(0.5f))

                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        Column {
            TextBone(
                modifier = Modifier.fillMaxWidth(0.6f),
                style = MaterialTheme.typography.headlineSmall,
            )

            TextBone(
                modifier = Modifier.fillMaxWidth(0.85f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        HeadingBone(widthFraction = 0.25f)

        AmountCardBone(index = 0)

        HeadingBone(widthFraction = 0.35f)

        repeat(BENEFICIARY_COUNT) { index ->
            Column {
                AmountCardBone(index = index + 1)

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/** "Paid by" or the beneficiaries' heading. */
@Composable
private fun HeadingBone(widthFraction: Float) {
    TextBone(
        modifier = Modifier
            .padding(Padding.small)
            .fillMaxWidth(widthFraction),
        style = MaterialTheme.typography.titleMedium,
    )
}

/** [UserAmountCard], the person's name and their amount to come. */
@Composable
private fun AmountCardBone(index: Int) {
    SkeletonPersonCard(nameWidth = skeletonLineWidth(index)) {
        TextBone(
            modifier = Modifier
                .padding(start = Padding.medium, end = Padding.large)
                .width(56.dp),
        )
    }
}

/**
 * An expense's history while it is read: [ExpenseVersionCard]s, when each version was made and
 * by whom, spaced as the history spaces them.
 */
@Composable
fun ExpenseHistorySkeleton(modifier: Modifier = Modifier) {
    Skeleton(
        modifier = modifier.padding(horizontal = Padding.medium),
        verticalArrangement = Arrangement.spacedBy(Padding.large),
    ) {
        repeat(VERSION_COUNT) { index ->
            SkeletonCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(Padding.medium)) {
                    TextBone(
                        modifier = Modifier.fillMaxWidth(0.55f),
                        style = MaterialTheme.typography.titleMedium,
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    TextBone(
                        modifier = Modifier.fillMaxWidth(skeletonLineWidth(index)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/** How many expenses each day of [ExpenseListSkeleton] has, more than a tall screen shows. */
private val EXPENSES_PER_DAY = listOf(2, 3, 1, 3, 2)

/** How many beneficiaries [ExpenseDetailSkeleton] lists. */
private const val BENEFICIARY_COUNT = 3

/** How many versions [ExpenseHistorySkeleton] lists, more than a tall screen shows. */
private const val VERSION_COUNT = 10
