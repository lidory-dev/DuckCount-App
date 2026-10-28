package app.lidory.duckcount.groupList

import app.lidory.duckcount.expenseList.utils.getCurrentTimestamp
import app.lidory.duckcount.getTransactionForGroup
import app.lidory.duckcount.groupList.callBacks.GroupSettleUpCallBacks
import app.lidory.duckcount.groupList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.Transaction
import database.data.repository.ExpenseRepository
import database.data.repository.GroupMemberRepository
import database.data.repository.GroupRepository
import database.data.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid
import database.domain.Beneficiary as DBBeneficiary
import database.domain.Currency as DBCurrency
import database.domain.ExpenseWithBeneficiaries as DBExpenseWithBeneficiaries

class GroupSettleUpCallBacksImpl(
    private val groupMemberRepository: GroupMemberRepository,
    private val userRepository: UserRepository,
    private val expenseRepository: ExpenseRepository,
    private val groupRepository: GroupRepository,
) : GroupSettleUpCallBacks {

    override suspend fun getGroupTransactions(
        groupUuid: String,
        groupMembers: List<GroupMember>,
        groupExpenses: List<ExpenseWithBeneficiaries>,
    ): List<Transaction> {
        return getTransactionForGroup(
            currency = getCurrency(groupUuid),
            members = groupMembers.map { groupMember ->
                groupMember.toDb()
            },
            expenses = groupExpenses.map { it.toDb() },
        ).map { transaction ->
            Transaction(
                debtorUuid = transaction.debtorUuid,
                creditorUuid = transaction.creditorUuid,
                amount = transaction.amount,
            )
        }
    }

    override fun getAllMembers(groupUuid: String): Flow<List<GroupMember>> {
        return groupMemberRepository.getMembersFromGroup(groupUuid = groupUuid).map { groupMembers ->
            groupMembers.map { it.toGroupListModel() }
        }
    }

    override fun getDisplayName(userUuid: String): Flow<String?> {
        return userRepository.getDisplayName(userUuid)
    }

    override fun getAllGroupActiveExpenses(groupUuid: String): Flow<List<ExpenseWithBeneficiaries>> {
        return expenseRepository.getAllFromGroupWithoutDeletedOnes(groupUuid = groupUuid)
            .map { expenses -> expenses.map { it.toGroupListModel() } }
    }

    override fun getAuthenticatedUserUuid(): String {
        return authenticationUtils.getAuthenticatedUserUuid() ?: ""
    }

    override fun isGroupLeft(groupUuid: String): Flow<Boolean> {
        return groupRepository.isLeft(uuid = groupUuid)
    }

    override suspend fun getCurrencySymbol(groupUuid: String): String {
        return getCurrency(groupUuid).symbol
    }

    private suspend fun getCurrency(groupUuid: String): DBCurrency {
        return groupRepository.get(groupUuid).first().currency
    }

    override suspend fun validateSettlement(
        creditorUuid: String,
        debtorUuid: String,
        amount: String,
        groupUuid: String,
    ) {
        // TODO: The UUID should come from the server.
        val expenseUuid = Uuid.random().toString()

        expenseRepository.insertWithBeneficiaries(
            DBExpenseWithBeneficiaries(
                uuid = expenseUuid,
                timestamp = getCurrentTimestamp(),
                title = "",
                description = "",
                amount = amount,
                creditorUuid = debtorUuid,
                isSettlement = true,
                currency = getCurrency(groupUuid),
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
