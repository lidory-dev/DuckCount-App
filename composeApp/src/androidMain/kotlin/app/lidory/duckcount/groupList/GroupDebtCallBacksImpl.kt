package app.lidory.duckcount.groupList

import app.lidory.duckcount.expenseList.utils.getCurrentTimestamp
import app.lidory.duckcount.getTransactionFromGroup
import app.lidory.duckcount.groupList.callBacks.GroupDebtCallBacks
import app.lidory.duckcount.groupList.model.Currency
import app.lidory.duckcount.groupList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupMemberWithFullName
import app.lidory.duckcount.groupList.model.TransactionWithFullName
import database.data.repository.ExpenseRepository
import database.data.repository.GroupMemberRepository
import database.data.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid
import database.domain.Beneficiary as DBBeneficiary
import database.domain.ExpenseWithBeneficiaries as DBExpenseWithBeneficiaries

class GroupDebtCallBacksImpl(
    private val groupMemberRepository: GroupMemberRepository,
    private val userRepository: UserRepository,
    private val expenseRepository: ExpenseRepository,
) : GroupDebtCallBacks {
    override suspend fun getGroupTransactions(
        groupUuid: String,
        groupMembers: List<GroupMember>,
        groupExpenses: List<ExpenseWithBeneficiaries>,
    ): List<TransactionWithFullName> {
        return getTransactionFromGroup(
            members = groupMembers.map { groupMember ->
                groupMember.toDb()
            },
            expenses = groupExpenses.map { it.toDb() },
        ).map { transaction ->
            TransactionWithFullName(
                debtorUuid = transaction.debtorUuid,
                debtorFullName = userRepository.getFullName(transaction.debtorUuid).first() ?: transaction.debtorUuid,
                creditorUuid = transaction.creditorUuid,
                creditorFullName = userRepository.getFullName(transaction.creditorUuid).first()
                    ?: transaction.creditorUuid,
                amount = transaction.amount,
            )
        }
    }

    override fun getAllMembers(groupUuid: String): Flow<List<GroupMemberWithFullName>> {
        return groupMemberRepository.getMembersFromGroup(groupUuid = groupUuid).map { groupMembers ->
            groupMembers.map { groupMember ->
                GroupMemberWithFullName(
                    member = GroupMember(
                        userUuid = groupMember.userUuid,
                        groupUuid = groupMember.groupUuid,
                        isAdmin = groupMember.isAdmin,
                    ),
                    // TODO: remove the check of isAdmin when the admin user is correctly inserted (see CreateGroupViewModel)
                    fullName = userRepository.getFullName(groupMember.userUuid).first() ?: groupMember.userUuid,
                )
            }
        }
    }

    override fun getAllGroupAtiveExpenses(groupUuid: String): Flow<List<ExpenseWithBeneficiaries>> {
        return expenseRepository.getAllFromGroupWithoutDeletedOnes(groupUuid = groupUuid)
            .map { expenses -> expenses.map { it.toGroupListModel() } }
    }

    override fun getAuthenticatedUserUuid(): String {
        return authenticationUtils.getAuthenticatedUserUuid() ?: ""
    }

    override fun getCurrencySymbol(groupUuid: String): String {
        return getCurrency(groupUuid).symbol
    }

    private fun getCurrency(groupUuid: String): Currency {
        // TODO: Get group currency when implemented
        return Currency(
            name = "EUR",
            symbol = "€",
            hasSubunits = true,
        )
    }

    override suspend fun refundExpense(creditorUuid: String, debtorUuid: String, amount: String, groupUuid: String) {
        val expenseUuid = Uuid.random().toString()
        expenseRepository.insertWithBeneficiaries(
            DBExpenseWithBeneficiaries(
                uuid = expenseUuid,
                timestamp = getCurrentTimestamp(),
                title = "Refund",
                description = "",
                amount = amount,
                creditorUuid = debtorUuid,
                isRefund = true,
                currency = getCurrency(groupUuid).toDb(),
                groupUuid = groupUuid,
                isDeleted = false,
                beneficiaries = listOf(
                    DBBeneficiary(
                        uuid = Uuid.random().toString(),
                        expenseUuid = expenseUuid,
                        userUuid = creditorUuid,
                        share = amount,
                        isShareFixed = true,
                    ),
                ),
            ),
        )
    }
}