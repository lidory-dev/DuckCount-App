package app.lidory.duckcount.expenseList

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.expenseList.component.CreditorCard
import app.lidory.duckcount.expenseList.component.ExpenseDetailActionDropDownMenu
import app.lidory.duckcount.expenseList.extensions.formatDate
import app.lidory.duckcount.expenseList.viewmodel.ExpenseDetailViewModel
import kotlinx.datetime.LocalDateTime
import org.koin.androidx.compose.koinViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseDetailScreen(
    modifier: Modifier = Modifier,
    viewModel: ExpenseDetailViewModel = koinViewModel(),
    onBack: () -> Unit,
    navigateToEditExpense: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val displayedBeneficiaries by viewModel.displayedBeneficiaries.collectAsStateWithLifecycle()
    val creditorFullName by viewModel.displayedCreditor.collectAsStateWithLifecycle()

    // Subscribe once (keyed on Unit, so it survives recomposition) to the ViewModel's
    // one-shot navigateBack events and forward them to navigation. The ViewModel emits
    // this after deleting an expense, since there is no detail left to show.
    LaunchedEffect(Unit) {
        viewModel.navigateBack.collect { onBack() }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    state.expenseWithBeneficiaries?.timestamp?.let { timestamp ->
                        val date = LocalDateTime.parse(timestamp)
                        Text(
                            text = stringResource(
                                R.string.date,
                                date.date.formatDate(),
                                "${date.time.hour}:${date.time.minute}",
                            ),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                    ) {
                        Icon(painter = painterResource(androidUiR.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
                actions = {
                    ExpenseDetailActionDropDownMenu(
                        isDeleted = state.expenseWithBeneficiaries?.isDeleted ?: false,
                        restore = viewModel::restoreExpense,
                        delete = viewModel::deleteExpense,
                        edit = dropUnlessResumed { navigateToEditExpense() },
                    )
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(
                top = innerPadding.calculateTopPadding(),
                start = Padding.medium,
                end = Padding.medium,
            ),
            verticalArrangement = Arrangement.spacedBy(Padding.small),
        ) {
            item {
                CreditorCard(
                    modifier = Modifier.fillMaxWidth(),
                    fullName = creditorFullName ?: "",
                    amount = state.expenseWithBeneficiaries?.amount ?: "0",
                    currency = state.expenseWithBeneficiaries?.currency?.symbol ?: "",
                )

                Text(
                    text = state.expenseWithBeneficiaries?.title ?: "",
                    style = MaterialTheme.typography.headlineSmall,
                    textDecoration =
                        if (state.expenseWithBeneficiaries?.isDeleted ?: false)
                            TextDecoration.LineThrough
                        else
                            null,
                )

                if (!state.expenseWithBeneficiaries?.description.isNullOrBlank())
                    Text(
                        text = state.expenseWithBeneficiaries?.description ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration =
                            if (state.expenseWithBeneficiaries?.isDeleted ?: false)
                                TextDecoration.LineThrough
                            else
                                null,
                    )

                Text(
                    modifier = Modifier.padding(Padding.small),
                    style = MaterialTheme.typography.titleLarge,
                    text = stringResource(R.string.beneficiaries),
                )
            }

            items(displayedBeneficiaries) { beneficiary ->

                Card(
                    shape = RoundedCornerShape(size = Padding.large),
                    modifier = Modifier
                        .padding(Padding.medium)
                        .fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = Padding.medium)
                            .height(TextFieldDefaults.MinHeight),
                    ) {

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // TODO: Replace with ProfilePicture when merged
                            var boxSize by remember { mutableStateOf(IntSize.Zero) }
                            Card(
                                modifier = Modifier
                                    .padding(Padding.medium)
                                    .aspectRatio(1f),
                                shape = CircleShape,
                                colors = CardDefaults.cardColors(
                                    containerColor = Color.Blue,
                                ),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .onSizeChanged { size -> boxSize = size },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val textSize = with(LocalDensity.current) {
                                        (boxSize.width * 0.5f).toSp()
                                    }

                                    Text(
                                        fontSize = textSize,
                                        text = beneficiary.fullName.take(1),
                                    )
                                }
                            }

                            Text(
                                text = beneficiary.fullName,
                            )
                        }
                        Text(text = "${beneficiary.share} ${state.expenseWithBeneficiaries?.currency?.symbol ?: ""}")
                    }
                }
            }
        }
    }
}

