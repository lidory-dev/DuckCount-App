package app.lidory.duckcount.groupList.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.groupList.callBacks.GroupBalanceCallBacks
import app.lidory.duckcount.groupList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupMemberWithBalance
import app.lidory.duckcount.groupList.model.GroupMemberWithFullName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class GroupBalanceViewModel(
    private val groupUuid: String,
    groupBalanceCallBacks: GroupBalanceCallBacks,
) : ViewModel() {
    val groupMembers: Flow<List<GroupMemberWithFullName>> = groupBalanceCallBacks.getAllMembers(groupUuid)

    val groupExpenses: Flow<List<ExpenseWithBeneficiaries>> = groupBalanceCallBacks.getAllGroupExpense(groupUuid)

    val state: StateFlow<MembersWithBalanceState> =
        combine(groupMembers, groupExpenses) { groupMembersWithFullName, groupExpenses ->
            val debts =
                groupBalanceCallBacks.getNetDebtsPerUser(
                    groupUuid = groupUuid,
                    groupMembers = groupMembersWithFullName.map { it.member },
                    groupExpenses = groupExpenses,
                )

            val memberUuids = groupMembersWithFullName.mapTo(HashSet()) { it.member.userUuid }

            // Whoever the creator removed, still named by the expenses, which keep what they owe
            // or are owed.
            val formerMembers = debts.filterKeys { it !in memberUuids }.map { (userUuid, balance) ->
                GroupMemberWithBalance(
                    member = GroupMemberWithFullName(
                        member = GroupMember(userUuid = userUuid, groupUuid = groupUuid, isAdmin = false),
                        fullName = groupBalanceCallBacks.getFullName(userUuid),
                    ),
                    balance = balance,
                    isFormerMember = true,
                )
            }

            MembersWithBalanceState.Content(
                members = groupMembersWithFullName.map { memberWithFullName ->
                    GroupMemberWithBalance(
                        member = memberWithFullName,
                        balance = debts[memberWithFullName.member.userUuid]
                            ?: memberWithFullName.member.userUuid, // TODO: Show that the username was not found?
                    )
                } + formerMembers,
                currencySymbol = groupBalanceCallBacks.getCurrencySymbol(groupUuid),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = MembersWithBalanceState.Loading,
        )
}

sealed interface MembersWithBalanceState {
    data object Loading : MembersWithBalanceState
    data class Content(
        val members: List<GroupMemberWithBalance>,
        val currencySymbol: String,
    ) : MembersWithBalanceState
}
