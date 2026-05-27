package app.lidory.duckcount.expenseList.callBacks

import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import kotlinx.coroutines.flow.Flow

interface ExpenseListCallBacks {

    fun getAllExpenses(): Flow<List<ExpenseWithBeneficiaries>>

}