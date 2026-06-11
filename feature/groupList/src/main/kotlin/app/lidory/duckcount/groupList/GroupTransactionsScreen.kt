package app.lidory.duckcount.groupList

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.component.DebtCard
import app.lidory.duckcount.groupList.component.RefundCard
import app.lidory.duckcount.groupList.viewmodel.GroupDebtViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun GroupTransactionsScreen(
    modifier: Modifier = Modifier,
    viewModel: GroupDebtViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.padding(Padding.medium),
    ) {
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
                if (transaction.creditorUuid == state.authenticatedUserUuid)
                    RefundCard(
                        modifier = Modifier.padding(Padding.medium),
                        debtorFullName = transaction.debtorFullName,
                        amount = transaction.amount,
                        onValidateRefund = { TODO() },
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