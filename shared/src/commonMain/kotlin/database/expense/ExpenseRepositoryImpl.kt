package database.expense

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ExpenseRepositoryImpl(
    // TODO: Add remote data source.
    private val expenseDao: ExpenseDao,
) : ExpenseRepository {

    override suspend fun insert(expense: Expense) {
        expenseDao.insert(expenseEntity = expense.toExpenseEntity())
    }

    override suspend fun update(expense: Expense) {
        expenseDao.update(expenseEntity = expense.toExpenseEntity())
    }

    override suspend fun delete(expense: Expense) {
        expenseDao.delete(expenseEntity = expense.toExpenseEntity())
    }

    override fun getAll(): Flow<List<ExpenseWithBeneficiaries>> {
        return expenseDao.getAllWithBeneficiariesAsFlow().map { expensesWithBeneficiariesEntities ->
            expensesWithBeneficiariesEntities.map { it.toExpenseWithBeneficiaries() }
        }
    }

    override fun getWithBeneficiaries(uuid: String): Flow<ExpenseWithBeneficiaries> {
        return expenseDao.getWithBeneficiariesAsFlow(uuid = uuid).map { expenseWithBeneficiariesEntity ->
            expenseWithBeneficiariesEntity.toExpenseWithBeneficiaries()
        }
    }
}
