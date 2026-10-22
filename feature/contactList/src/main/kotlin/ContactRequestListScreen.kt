import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.contactList.R
import app.lidory.duckcount.android.ui.composable.DuckCountSnackbarHost
import app.lidory.duckcount.android.ui.composable.EmptyContentContainer
import app.lidory.duckcount.android.ui.constant.Padding
import component.ContactRequestCard
import model.AnswerContactRequestError
import model.messageRes
import org.koin.androidx.compose.koinViewModel
import viewmodel.ContactRequestListState
import viewmodel.ContactRequestListViewModel
import app.lidory.duckcount.android.ui.R as SharedR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactRequestListScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<ContactRequestListViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var pendingError by remember { mutableStateOf<AnswerContactRequestError?>(null) }

    LaunchedEffect(Unit) {
        viewModel.errors.collect { pendingError = it }
    }

    pendingError?.let { error ->
        val message = stringResource(error.messageRes())

        LaunchedEffect(error) {
            snackbarHostState.showSnackbar(message)
            pendingError = null
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.contact_requests),
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                    ) {
                        Icon(
                            painter = painterResource(SharedR.drawable.ic_arrow_back),
                            contentDescription = stringResource(SharedR.string.back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val content = state as? ContactRequestListState.Content

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                content == null || (content.requests.isEmpty() && content.isRefreshing) -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                content.requests.isEmpty() -> {
                    EmptyContentContainer(
                        modifier = Modifier.weight(1f),
                        icon = R.drawable.ic_envelop_checked,
                        text = R.string.no_contact_requests,
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = Padding.medium),
                        verticalArrangement = Arrangement.spacedBy(Padding.medium),
                    ) {
                        items(
                            items = content.requests,
                            key = { request -> request.uuid },
                        ) { request ->
                            ContactRequestCard(
                                modifier = Modifier.animateItem(),
                                request = request,
                                answer = content.answers[request.uuid],
                                onAccept = { viewModel.accept(requestUuid = request.uuid) },
                                onRefuse = { viewModel.refuse(requestUuid = request.uuid) },
                            )
                        }
                    }
                }
            }

            DuckCountSnackbarHost(hostState = snackbarHostState)
        }
    }
}
