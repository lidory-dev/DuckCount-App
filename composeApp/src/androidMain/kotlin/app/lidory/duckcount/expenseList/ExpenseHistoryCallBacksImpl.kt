package app.lidory.duckcount.expenseList

import app.lidory.duckcount.expenseList.callBacks.ExpenseHistoryCallBacks
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.PreviousBeneficiary
import app.lidory.duckcount.expenseList.model.PreviousExpenseUi
import database.data.repository.ExpenseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class ExpenseHistoryCallBacksImpl(
    val expenseRepository: ExpenseRepository,
) : ExpenseHistoryCallBacks {
    override fun getPreviousExpenses(groupUuid: String?, expenseUuid: String): Flow<List<PreviousExpenseUi>> {
        // TODO: Implement the server route
        return flowOf(
            listOf(
                PreviousExpenseUi(
                    modifiedAt = "2026-06-29T16:55:59.017404",
                    modifiedUserFullName = "Alice Martin",
                    previousAmount = "20",
                    previousBeneficiaries = listOf(
                        PreviousBeneficiary(
                            userUuid = "c901f183-7209-4706-bac5-cd539e116ab8",
                            share = "10",
                            fullName = "Bob Dylan",
                        ),
                        PreviousBeneficiary(
                            userUuid = "5246201e-2e3e-4a52-abda-6161847c8779",
                            share = "10",
                            fullName = "Alice Martin",
                        ),
                    ),
                    previousCreditorFullName = "Alice Martin",
                    previousDescription = "Delicious pizzas",
                    previousIsRefund = false,
                    previousTimestamp = "2026-06-29T16:50:59.017404",
                    previousTitle = "Restaurant Paris",
                ),
                PreviousExpenseUi(
                    modifiedAt = "2026-06-29T16:53:59.017404",
                    modifiedUserFullName = "Bob Dylan",
                    previousAmount = "23",
                    previousBeneficiaries = listOf(
                        PreviousBeneficiary(
                            userUuid = "c901f183-7209-4706-bac5-cd539e116ab8",
                            share = "7.67",
                            fullName = "Bob Dylan",
                        ),
                        PreviousBeneficiary(
                            userUuid = "5246201e-2e3e-4a52-abda-6161847c8779",
                            share = "7.66",
                            fullName = "Alice Martin",
                        ),
                    ),
                    previousCreditorFullName = "Alice Martin",
                    previousDescription = "Delicious pizzas",
                    previousIsRefund = false,
                    previousTimestamp = "2026-06-29T16:50:59.017404",
                    previousTitle = "Restaurant Paris",
                ),
                PreviousExpenseUi(
                    modifiedAt = "2026-06-29T16:52:59.017404",
                    modifiedUserFullName = "Bob Dylan",
                    previousAmount = "23",
                    previousBeneficiaries = listOf(
                        PreviousBeneficiary(
                            userUuid = "c901f183-7209-4706-bac5-cd539e116ab8",
                            share = "7.67",
                            fullName = "Bob Dylan",
                        ),
                        PreviousBeneficiary(
                            userUuid = "5246201e-2e3e-4a52-abda-6161847c8779",
                            share = "7.66",
                            fullName = "Alice Martin",
                        ),
                    ),
                    previousCreditorFullName = "Alice Martin",
                    previousDescription = "",
                    previousIsRefund = false,
                    previousTimestamp = "2026-06-29T16:50:59.017404",
                    previousTitle = "Restaurant Paris pizzas",
                ),
                PreviousExpenseUi(
                    modifiedAt = "2026-06-29T16:50:59.017404",
                    modifiedUserFullName = "Bob Dylan",
                    previousAmount = "33",
                    previousBeneficiaries = listOf(
                        PreviousBeneficiary(
                            userUuid = "c901f183-7209-4706-bac5-cd539e116ab8",
                            share = "11",
                            fullName = "Bob Dylan",
                        ),
                        PreviousBeneficiary(
                            userUuid = "5246201e-2e3e-4a52-abda-6161847c8779",
                            share = "11",
                            fullName = "Alice Martin",
                        ),
                        PreviousBeneficiary(
                            userUuid = "5246201e-2e3e-4a52-abda-6161847c8779",
                            share = "11",
                            fullName = "Frank Sinatra",
                        ),
                    ),
                    previousCreditorFullName = "Alice Martin",
                    previousDescription = "",
                    previousIsRefund = false,
                    previousTimestamp = "2026-06-29T16:50:59.017404",
                    previousTitle = "Restaurant Paris pizzas",
                ),
            ),
        )
    }

    override fun getCurrency(expenseUuid: String): Flow<Currency?> {
        return expenseRepository.getCurrency(expenseUuid).map { it?.toModel() }
    }
}