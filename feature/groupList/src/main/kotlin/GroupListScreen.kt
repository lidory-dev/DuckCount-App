import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.R
import app.lidory.duckcount.android.ui.constant.Padding
import component.GroupCard
import org.koin.androidx.compose.koinViewModel
import viewmodel.GroupListViewModel


@Composable
fun GroupListScreen() {

    val viewModel = koinViewModel<GroupListViewModel>()

    // TODO: Replace tempState with real state
    val state = viewModel.tempState
    //val state by viewModel.state.collectAsStateWithLifecycle()

    if (state.groups.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = stringResource(R.string.no_groups))
        }
    } else {

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = Padding.medium,
                ),
            verticalArrangement = Arrangement.spacedBy(Padding.medium),
        ) {
            items(
                items = state.groups,
                key = { it.uuid },
            ) { group ->
                GroupCard(
                    title = group.title,
                    description = group.description,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}