package app.lidory.duckcount.groupList.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.groupList.callBacks.GroupBalanceCallBacks
import app.lidory.duckcount.groupList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupMemberWithBalance
import app.lidory.duckcount.groupList.model.GroupMemberWithFullName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class GroupBalanceViewModel(
    private val groupUuid: String,
    groupBalanceCallBacks: GroupBalanceCallBacks,
) : ViewModel() {
    val groupMembers: Flow<List<GroupMember>> = groupBalanceCallBacks.getAllMembers(groupUuid)

    val groupExpenses: Flow<List<ExpenseWithBeneficiaries>> = groupBalanceCallBacks.getAllGroupExpense(groupUuid)

    // The balances are worked out again as the members or the expenses change, and named anew as a
    // name does, which takes no working out.
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<MembersWithBalanceState> =
        combine(groupMembers, groupExpenses) { groupMembers, groupExpenses ->
            val debts =
                groupBalanceCallBacks.getNetDebtsPerUser(
                    groupUuid = groupUuid,
                    groupMembers = groupMembers,
                    groupExpenses = groupExpenses,
                )
            val currencySymbol = groupBalanceCallBacks.getCurrencySymbol(groupUuid)

            membersWithBalance(
                groupUuid = groupUuid,
                members = groupMembers,
                debts = debts,
                nameOf = groupBalanceCallBacks::getDisplayName,
            ).map { members ->
                MembersWithBalanceState.Content(members = members, currencySymbol = currencySymbol)
            }
        }.flatMapLatest { content -> content }.stateIn(
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

/**
 * Emits what each of the [members] of the group [groupUuid] owes or is owed, [debts], and each of
 * its former members, each time one of their names changes, named as [nameOf] tells, see
 * [displayNames].
 *
 * The former members are whoever else [debts] names: whoever the creator removed, still named by
 * the expenses, which keep what they owe or are owed, and under the name they had, which this
 * device keeps, see [GroupBalanceCallBacks.getDisplayName].
 */
fun membersWithBalance(
    groupUuid: String,
    members: List<GroupMember>,
    debts: Map<String, String>,
    nameOf: (userUuid: String) -> Flow<String?>,
): Flow<List<GroupMemberWithBalance>> {
    val memberUuids = members.mapTo(HashSet()) { it.userUuid }
    val formerMemberUuids = debts.keys.filter { it !in memberUuids }

    return displayNames(userUuids = memberUuids + formerMemberUuids, nameOf = nameOf).map { names ->
        members.map { member ->
            GroupMemberWithBalance(
                member = GroupMemberWithFullName(member = member, fullName = names[member.userUuid]),
                balance = debts[member.userUuid]
                    ?: member.userUuid, // TODO: Show that the username was not found?
            )
        } + formerMemberUuids.map { userUuid ->
            GroupMemberWithBalance(
                member = GroupMemberWithFullName(
                    member = GroupMember(userUuid = userUuid, groupUuid = groupUuid, isAdmin = false),
                    fullName = names[userUuid],
                ),
                balance = debts.getValue(userUuid),
                isFormerMember = true,
            )
        }
    }
}
