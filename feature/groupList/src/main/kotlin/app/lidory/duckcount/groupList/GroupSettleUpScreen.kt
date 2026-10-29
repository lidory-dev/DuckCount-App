package app.lidory.duckcount.groupList

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.composable.EmptyContentContainer
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.groupList.component.DebtCard
import app.lidory.duckcount.groupList.component.SettlementCard
import app.lidory.duckcount.groupList.viewmodel.DebtSide
import app.lidory.duckcount.groupList.viewmodel.GroupSettleUpState
import app.lidory.duckcount.groupList.viewmodel.GroupSettleUpViewModel
import app.lidory.duckcount.groupList.viewmodel.sideFor
import org.koin.androidx.compose.koinViewModel

@Composable
fun GroupSettleUpScreen(
    modifier: Modifier = Modifier,
    viewModel: GroupSettleUpViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (val state = state) {
        GroupSettleUpState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        is GroupSettleUpState.Content -> {
            if (state.transactions.isEmpty()) {
                EmptyContentContainer(
                    icon = R.drawable.ic_checkbook,
                    text = R.string.you_are_all_settled_up,
                )
            } else {
                LazyColumn(
                    modifier = modifier.padding(Padding.medium),
                ) {
                    items(
                        items = state.transactions,
                        key = { "${it.debtorUuid}-${it.creditorUuid}-${it.amount}" },
                    ) { transaction ->
                        val debtorFullName = transaction.debtorFullName ?: stringResource(R.string.unknown_member)
                        val creditorFullName = transaction.creditorFullName ?: stringResource(R.string.unknown_member)
                        val amount = "${transaction.amount} ${state.currencySymbol}"

                        when (transaction.sideFor(userUuid = state.authenticatedUserUuid)) {
                            DebtSide.OWED_TO_USER -> SettlementCard(
                                modifier = Modifier.padding(Padding.medium),
                                debtorFullName = debtorFullName,
                                amount = amount,
                                onValidateSettlement = if (state.canSettle) {
                                    {
                                        viewModel.validateSettlement(
                                            transaction.creditorUuid,
                                            transaction.debtorUuid,
                                            transaction.amount,
                                        )
                                    }
                                } else {
                                    null
                                },
                            )

                            DebtSide.OWED_BY_USER -> DebtCard(
                                modifier = Modifier.padding(Padding.medium),
                                text = stringResource(R.string.you_owe, creditorFullName, amount),
                            )

                            DebtSide.BETWEEN_OTHERS -> DebtCard(
                                modifier = Modifier.padding(Padding.medium),
                                text = stringResource(
                                    R.string.member_owes_member,
                                    debtorFullName,
                                    creditorFullName,
                                    amount,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
