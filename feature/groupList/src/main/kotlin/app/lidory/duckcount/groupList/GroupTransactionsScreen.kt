package app.lidory.duckcount.groupList

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.component.DebtCard
import app.lidory.duckcount.groupList.component.RefundCard
import app.lidory.duckcount.groupList.viewmodel.GroupTransactionsState
import app.lidory.duckcount.groupList.viewmodel.GroupTransactionsViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun GroupTransactionsScreen(
    modifier: Modifier = Modifier,
    viewModel: GroupTransactionsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.padding(Padding.medium),
    ) {
        when (val state = state) {
            GroupTransactionsState.Loading -> {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            GroupTransactionsState.NotFound -> {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = stringResource(R.string.no_transactions))
                    }
                }
            }

            is GroupTransactionsState.Content -> {
                if (state.transactions.isEmpty()) {
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            Text(text = stringResource(R.string.no_transactions))
                        }
                    }
                } else {
                    items(
                        items = state.transactions,
                        key = { "${it.debtorFullName}-${it.creditorFullName}-${it.amount}" },
                    ) { transaction ->
                        Log.e(
                            "LUCA PRINT",
                            "GroupTransactionsScreen: ${transaction.creditorUuid} / ${state.authenticatedUserUuid}",
                        )
                        if (transaction.creditorUuid == state.authenticatedUserUuid)
                            RefundCard(
                                modifier = Modifier.padding(Padding.medium),
                                debtorFullName = transaction.debtorFullName,
                                amount = transaction.amount,
                                onValidateRefund = {
                                    viewModel.refundExpense(
                                        transaction.creditorUuid,
                                        transaction.debtorUuid,
                                        transaction.amount,
                                    )
                                },
                            )
                        else
                            DebtCard(
                                modifier = Modifier.padding(Padding.medium),
                                creditorFullName = transaction.creditorFullName,
                                amount = transaction.amount,
                            )
                    }
                }
            }
        }
    }
}