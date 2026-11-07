package app.lidory.duckcount.groupList.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.tab.Tab
import kotlinx.coroutines.launch

/** What Material's [Tab] leaves on each side of its text. */
private val TabTextPadding = 16.dp

private val TabIconSize = 24.dp

@Composable
fun GroupPager(
    pagerState: PagerState,
    tabs: List<Tab>,
    expenseListContent: @Composable () -> Unit,
    balanceContent: @Composable () -> Unit,
    settleUpContent: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val titles = tabs.map { it.title() }

    BoxWithConstraints {
        // Every tab gets the same width, so a long title ("Rembourser") can run out of room beside
        // its icon on a narrow screen or with a larger font, where it would break in the middle of
        // the word. All the icons then go above their titles, which leaves each title the tab's
        // whole width: a row of tabs is laid out one way or the other, never both.
        val titlesFitBesideIcons = titlesFitBesideIcons(titles, tabWidthPx = constraints.maxWidth / tabs.size)

        PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
            tabs.forEachIndexed { index, tab ->
                val selected = pagerState.currentPage == index
                val onClick: () -> Unit = { scope.launch { pagerState.animateScrollToPage(index) } }
                // The title says what the icon does.
                val icon = @Composable {
                    Icon(painter = tab.icon(), contentDescription = null, modifier = Modifier.size(TabIconSize))
                }

                if (titlesFitBesideIcons) {
                    Tab(
                        selected = selected,
                        onClick = onClick,
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Padding.small),
                            ) {
                                icon()
                                TabTitle(titles[index])
                            }
                        },
                    )
                } else {
                    Tab(
                        selected = selected,
                        onClick = onClick,
                        text = { TabTitle(titles[index]) },
                        icon = icon,
                    )
                }
            }
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.Top,
    ) { page ->
        when (page) {
            0 -> expenseListContent()
            1 -> balanceContent()
            2 -> settleUpContent()
        }
    }
}

/** Whether each title stays on one line beside its icon in a tab [tabWidthPx] wide. */
@Composable
private fun titlesFitBesideIcons(titles: List<String>, tabWidthPx: Int): Boolean {
    val textMeasurer = rememberTextMeasurer()
    // The style Material's Tab gives its text.
    val style = MaterialTheme.typography.titleSmall
    val density = LocalDensity.current

    return remember(titles, tabWidthPx, style, density, textMeasurer) {
        val room = with(density) {
            tabWidthPx - 2 * TabTextPadding.roundToPx() - TabIconSize.roundToPx() - Padding.small.roundToPx()
        }
        // Broken into lines as the title would be there, rather than compared by width, which a
        // title a fraction of a pixel wider than its room still fits by.
        room > 0 && titles.all { title ->
            textMeasurer.measure(title, style, constraints = Constraints(maxWidth = room)).lineCount == 1
        }
    }
}

/**
 * A tab's title, on one line whatever the room: a title that does not fit even above its icon, at
 * the largest font sizes, is made smaller, down to the theme's smallest text at the default font
 * size, and only then cut.
 */
@Composable
private fun TabTitle(title: String) {
    val density = LocalDensity.current
    // In sp, the larger font size a user chose would scale the smallest size up too.
    val minFontSize = with(density) { (MaterialTheme.typography.labelSmall.fontSize.value * density.density).toSp() }

    Text(
        text = title,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = minFontSize, maxFontSize = LocalTextStyle.current.fontSize),
    )
}
