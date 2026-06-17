package database.expense

import database.beneficiary.Beneficiary
import database.beneficiary.toBeneficiaryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ExpenseRepositoryImpl(
    // TODO: Add remote data source.
    private val expenseDao: ExpenseDao,
) : ExpenseRepository {

    override suspend fun insert(expense: Expense) {
        expenseDao.insert(expenseEntity = expense.toExpenseEntity())
    }

    override suspend fun insertBeneficiaries(beneficiaries: List<Beneficiary>) {
        expenseDao.insertBeneficiaries(beneficiaries = beneficiaries.map { it.toBeneficiaryEntity() })
    }

    override suspend fun insertWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiaries) {
        expenseDao.insertWithBeneficiaries(expenseWithBeneficiaries = expenseWithBeneficiaries.toExpenseWithBeneficiariesEntity())
    }

    override suspend fun update(expense: Expense) {
        expenseDao.update(expenseEntity = expense.toExpenseEntity())
    }

    override suspend fun updateWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiaries) {
        expenseDao.updateWithBeneficiaries(expenseWithBeneficiaries = expenseWithBeneficiaries.toExpenseWithBeneficiariesEntity())
    }

    override suspend fun delete(expense: Expense) {
        expenseDao.delete(expenseEntity = expense.toExpenseEntity())
    }

    override suspend fun delete(expenseUuid: String) {
        expenseDao.delete(expenseUuid)
    }

    override suspend fun restore(expenseUuid: String) {
        expenseDao.restore(expenseUuid)
    }

    override fun getAll(): Flow<List<ExpenseWithBeneficiaries>> {
        return expenseDao.getAllWithBeneficiariesAsFlow().map { expensesWithBeneficiariesEntities ->
            expensesWithBeneficiariesEntities.map { it.toExpenseWithBeneficiaries() }
        }
    }

    override fun getAllFromGroup(groupUuid: String): Flow<List<ExpenseWithBeneficiaries>> {
        return expenseDao.getAllFromGroupAsFlow(groupUuid).map { expense ->
            expense.map { it.toExpenseWithBeneficiaries() }
        }
    }

    override fun getAllFromGroupWithoutDeletedOnes(groupUuid: String): Flow<List<ExpenseWithBeneficiaries>> {
        return expenseDao.getAllFromGroupWithoutDeletedOnesAsFlow(groupUuid).map { expense ->
            expense.map { it.toExpenseWithBeneficiaries() }
        }
    }

    override fun getAllWithoutGroup(): Flow<List<ExpenseWithBeneficiaries>> {
        return expenseDao.getAllWithoutGroupAsFlow().map { expenseWithBeneficiariesEntities ->
            expenseWithBeneficiariesEntities.map { it.toExpenseWithBeneficiaries() }
        }
    }

    override fun getWithBeneficiaries(uuid: String): Flow<ExpenseWithBeneficiaries?> {
        return expenseDao.getWithBeneficiariesAsFlow(uuid = uuid).map { expenseWithBeneficiariesEntity ->
            expenseWithBeneficiariesEntity?.toExpenseWithBeneficiaries()
        }
    }
}
