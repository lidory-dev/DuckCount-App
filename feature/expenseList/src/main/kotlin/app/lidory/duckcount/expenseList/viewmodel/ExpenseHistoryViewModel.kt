package app.lidory.duckcount.expenseList.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.expenseList.callBacks.ExpenseHistoryCallBacks
import app.lidory.duckcount.expenseList.model.ExpenseHistory
import app.lidory.duckcount.expenseList.model.ExpenseHistoryError
import app.lidory.duckcount.expenseList.model.ExpenseVersion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseHistoryViewModel(
    expenseUuid: String,
    private val expenseHistoryCallBacks: ExpenseHistoryCallBacks,
) : ViewModel() {

    /** Read once, when the screen first asks: it comes from the server. */
    val state: StateFlow<ExpenseHistoryState> =
        flow { emit(expenseHistoryCallBacks.getHistory(expenseUuid = expenseUuid)) }
            .flatMapLatest { history ->
                when (history) {
                    is ExpenseHistory.Unavailable -> flowOf(ExpenseHistoryState.Failed(error = history.error))

                    is ExpenseHistory.Versions ->
                        expenseHistoryCallBacks.fullNames(userUuids = history.versions.map { it.authorUuid })
                            .map { fullNames ->
                                ExpenseHistoryState.Content(versions = history.versions, fullNames = fullNames)
                            }
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Lazily,
                initialValue = ExpenseHistoryState.Loading,
            )
}

sealed interface ExpenseHistoryState {

    data object Loading : ExpenseHistoryState

    data class Failed(val error: ExpenseHistoryError) : ExpenseHistoryState

    data class Content(
        val versions: List<ExpenseVersion>,

        /** By user, null for somebody this device holds no profile for. */
        val fullNames: Map<String, String?>,
    ) : ExpenseHistoryState
}

/** The name this device holds for each of [userUuids], or null for somebody it holds none for. */
internal fun ExpenseHistoryCallBacks.fullNames(userUuids: List<String>): Flow<Map<String, String?>> {
    val distinct = userUuids.distinct()

    if (distinct.isEmpty()) return flowOf(emptyMap())

    return combine(distinct.map { uuid -> getUserFullName(uuid).map { name -> uuid to name } }) { pairs ->
        pairs.toMap()
    }
}
