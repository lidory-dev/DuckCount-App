package app.lidory.duckcount.expenseList

import app.lidory.duckcount.ExpenseError
import app.lidory.duckcount.MAX_DESCRIPTION_LENGTH
import app.lidory.duckcount.expenseList.callBacks.CreateExpenseCallBacks
import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.Expense
import app.lidory.duckcount.expenseList.model.User
import database.beneficiary.BeneficiaryRepository
import database.expense.ExpenseRepository
import database.user.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import app.lidory.duckcount.expenseList.R as expenseListR
import app.lidory.duckcount.validateExpense as validateExpenseShared
import database.beneficiary.Beneficiary as BeneficiaryDB
import database.currency.Currency as CurrencyDB
import database.expense.Expense as ExpenseDB

class CreateExpenseCallBacksImpl(
    private val expenseRepository: ExpenseRepository,
    private val userRepository: UserRepository,
    private val beneficiaryRepository: BeneficiaryRepository,
) : CreateExpenseCallBacks {

    override val maxDescriptionLength: Int = MAX_DESCRIPTION_LENGTH

    override suspend fun insert(expense: Expense) {
        expenseRepository.insert(
            ExpenseDB(
                uuid = expense.uuid,
                timestamp = expense.timestamp,
                title = expense.title,
                description = expense.description,
                amount = expense.amount,
                creditorUuid = expense.creditorUuid,
                isRefund = expense.isRefund,
                currency = CurrencyDB(
                    uuid = expense.currency.uuid,
                    symbol = expense.currency.symbol,
                    name = expense.currency.name,
                    hasSubunits = expense.currency.hasSubunits,
                ),
            ),
        )
    }

    override fun getAllUsersFiltered(filter: String): Flow<List<User>> {
        return userRepository.getAllFiltered(filter).map { users ->
            users.map { user ->
                User(
                    uuid = user.uuid,
                    username = user.username,
                    fullName = user.fullName,
                )
            }
        }
    }

    override suspend fun insertBeneficiary(beneficiary: Beneficiary) {
        beneficiaryRepository.insert(
            BeneficiaryDB(
                uuid = beneficiary.uuid,
                expenseUuid = beneficiary.expenseUuid,
                userUuid = beneficiary.userUuid,
                share = beneficiary.share,
            ),
        )
    }

    override fun validateExpense(amount: String, currency: Currency, beneficiaries: List<Beneficiary>): Int? {
        val error = validateExpenseShared(
            amount,
            CurrencyDB(
                uuid = currency.uuid,
                name = currency.name,
                symbol = currency.symbol,
                hasSubunits = currency.hasSubunits,
            ),
            beneficiaries = beneficiaries.map { beneficiary ->
                BeneficiaryDB(
                    uuid = beneficiary.uuid,
                    expenseUuid = beneficiary.expenseUuid,
                    userUuid = beneficiary.userUuid,
                    share = beneficiary.share,
                )
            },
        )
        return when (error) {
            ExpenseError.SHARES_BELOW_TOTAL_AMOUNT -> {
                expenseListR.string.shares_below_total_amount
            }

            ExpenseError.SHARES_EXCEED_TOTAL_AMOUNT -> {
                expenseListR.string.shares_exceed_total_amount
            }

            ExpenseError.NON_POSITIVE_SHARE -> {
                expenseListR.string.non_positive_amount_share
            }

            ExpenseError.SHARES_EMPTY -> {
                expenseListR.string.non_positive_amount_share
            }

            else -> null
        }
    }
}