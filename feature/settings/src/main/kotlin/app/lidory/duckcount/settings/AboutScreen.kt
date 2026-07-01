package app.lidory.duckcount.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.settings.component.PromotionalCard
import app.lidory.duckcount.settings.viewmodel.AboutViewModel
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

private const val EASTER_EGG_TAP_COUNT = 5
private const val EASTER_EGG_TAP_WINDOW_MS = 500L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val viewModel: AboutViewModel = koinViewModel()

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapTime by remember { mutableLongStateOf(0L) }

    val quackMessage = stringResource(R.string.quack)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.about)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                    ) {
                        Icon(
                            painter = painterResource(androidUiR.drawable.ic_arrow_back),
                            contentDescription = stringResource(androidUiR.string.back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
        ) {
            item {
                PromotionalCard(
                    modifier = Modifier.padding(horizontal = Padding.large),
                    learnMoreButtonUrl = viewModel.learnMoreLink,
                )

                Spacer(modifier = Modifier.height(24.dp))
            }

            item {
                Text(
                    modifier = Modifier
                        .padding(horizontal = Padding.medium)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            val now = System.currentTimeMillis()
                            tapCount = if (now - lastTapTime <= EASTER_EGG_TAP_WINDOW_MS) {
                                tapCount + 1
                            } else {
                                1
                            }
                            lastTapTime = now

                            if (tapCount >= EASTER_EGG_TAP_COUNT) {
                                tapCount = 0
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        message = quackMessage,
                                    )
                                }
                            }
                        },
                    text = stringResource(
                        R.string.app_version,
                        viewModel.appVersionName,
                    ),
                )

                Spacer(modifier = Modifier.height(12.dp))
            }

            item {
                HorizontalDivider(Modifier, DividerDefaults.Thickness)

                Spacer(modifier = Modifier.height(12.dp))
            }

            item {
                Text(
                    modifier = Modifier.padding(horizontal = Padding.medium),
                    text = stringResource(R.string.trademarks_of_lidory),
                )
            }
        }
    }
}
