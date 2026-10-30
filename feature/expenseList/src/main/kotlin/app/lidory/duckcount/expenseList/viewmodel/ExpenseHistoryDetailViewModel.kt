package app.lidory.duckcount.expenseList.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.android.ui.text.sortedByName
import app.lidory.duckcount.expenseList.callBacks.ExpenseHistoryCallBacks
import app.lidory.duckcount.expenseList.model.BeneficiaryUi
import app.lidory.duckcount.expenseList.model.ExpenseHistory
import app.lidory.duckcount.expenseList.model.ExpenseHistoryError
import app.lidory.duckcount.expenseList.model.ExpenseVersion
import app.lidory.duckcount.expenseList.model.VersionChange
import app.lidory.duckcount.expenseList.model.changeFrom
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseHistoryDetailViewModel(
    expenseUuid: String,

    /**
     * Which version to show, by its [ExpenseVersion.revision] rather than its place in the list:
     * the history is read again here, and a version the device catches up with in between puts
     * one more in front of it.
     */
    revision: Long,

    private val expenseHistoryCallBacks: ExpenseHistoryCallBacks,
) : ViewModel() {

    /** Read once, when the screen first asks: it comes from the server. */
    val state: StateFlow<ExpenseHistoryDetailState> =
        flow { emit(expenseHistoryCallBacks.getHistory(expenseUuid = expenseUuid)) }
            .flatMapLatest { history ->
                val versions = when (history) {
                    is ExpenseHistory.Unavailable ->
                        return@flatMapLatest flowOf(ExpenseHistoryDetailState.Failed(error = history.error))

                    is ExpenseHistory.Versions -> history.versions
                }

                val index = versions.indexOfFirst { it.revision == revision }
                val version = versions.getOrNull(index)
                    ?: return@flatMapLatest flowOf(ExpenseHistoryDetailState.Failed(ExpenseHistoryError.UNKNOWN))

                // The one before it is next in the list, newest first.
                val change = version.changeFrom(earlier = versions.getOrNull(index + 1))

                val expense = version.expense

                expenseHistoryCallBacks.fullNames(
                    userUuids = listOf(version.authorUuid, expense.creditorUuid) +
                            expense.beneficiaries.map { it.userUuid },
                ).map { fullNames ->
                    ExpenseHistoryDetailState.Content(
                        version = version,
                        change = change,
                        authorFullName = fullNames[version.authorUuid],
                        creditorFullName = fullNames[expense.creditorUuid],
                        // As the expense's own screen lists them, see sortedByName.
                        beneficiaries = expense.beneficiaries
                            .map { beneficiary ->
                                BeneficiaryUi(
                                    uuid = beneficiary.uuid,
                                    userUuid = beneficiary.userUuid,
                                    share = beneficiary.share,
                                    fullName = fullNames[beneficiary.userUuid],
                                    isShareFixed = beneficiary.isShareFixed,
                                )
                            }
                            .sortedByName(
                                selfUuid = expenseHistoryCallBacks.getAuthenticatedUserUuid(),
                                uuidOf = { it.userUuid },
                                nameOf = { it.fullName },
                            ),
                    )
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Lazily,
                initialValue = ExpenseHistoryDetailState.Loading,
            )
}

sealed interface ExpenseHistoryDetailState {

    data object Loading : ExpenseHistoryDetailState

    data class Failed(val error: ExpenseHistoryError) : ExpenseHistoryDetailState

    /** Each name null for somebody this device holds no profile for. */
    data class Content(
        val version: ExpenseVersion,

        /** What [version] did to the expense, see [changeFrom]. */
        val change: VersionChange,

        val authorFullName: String?,
        val creditorFullName: String?,
        val beneficiaries: List<BeneficiaryUi>,
    ) : ExpenseHistoryDetailState
}
