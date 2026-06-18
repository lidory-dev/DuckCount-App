package app.lidory.duckcount.expenseList.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.expenseList.callBacks.ExpenseDetailCallBacks
import app.lidory.duckcount.expenseList.model.BeneficiaryUi
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ExpenseDetailViewModel(
    private val expenseUuid: String,
    private val expenseDetailCallBacks: ExpenseDetailCallBacks,
) : ViewModel() {

    private val _navigateBack = Channel<Unit>(Channel.BUFFERED)
    val navigateBack: Flow<Unit> = _navigateBack.receiveAsFlow()

    private val _state = MutableStateFlow(ExpenseScreenState())

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ExpenseScreenState> = _state
        .flatMapLatest {
            expenseDetailCallBacks.getExpenseWithBeneficiaries(uuid = expenseUuid)
        }
        .map { expenses ->
            ExpenseScreenState(expenseWithBeneficiaries = expenses)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = ExpenseScreenState(),
        )

    fun deleteExpense() {
        viewModelScope.launch {
            expenseDetailCallBacks.deleteExpense(expenseUuid)
            _navigateBack.send(Unit)
        }
    }

    fun restoreExpense() {
        viewModelScope.launch {
            expenseDetailCallBacks.restoreExpense(expenseUuid)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val beneficiaryNames: Flow<Map<String, String?>> =
        state
            .map { state -> state.expenseWithBeneficiaries?.beneficiaries?.map { it.userUuid } ?: emptyList() }
            .distinctUntilChanged()
            .flatMapLatest { userUuids ->
                if (userUuids.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(
                        userUuids.map { uuid ->
                            expenseDetailCallBacks.getUserFullName(uuid)
                                .map { name -> uuid to name }
                        },
                    ) { pairs -> pairs.toMap() }
                }
            }

    val displayedBeneficiaries: StateFlow<List<BeneficiaryUi>> =
        combine(
            state.map { it.expenseWithBeneficiaries?.beneficiaries }.distinctUntilChanged(),
            beneficiaryNames,
        ) { beneficiaries, names ->
            beneficiaries?.map { beneficiary ->
                BeneficiaryUi(
                    uuid = beneficiary.uuid,
                    userUuid = beneficiary.userUuid,
                    share = beneficiary.share,
                    fullName = names[beneficiary.userUuid] ?: "",
                )
            } ?: emptyList()
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList(),
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val displayedCreditor: StateFlow<String?> =
        state
            .map { it.expenseWithBeneficiaries?.creditorUuid }
            .filterNotNull()
            .flatMapLatest { uuid ->
                expenseDetailCallBacks.getUserFullName(uuid)
                    .catch { emit("") }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = "",
            )

    fun getUserFullName(userUuid: String): Flow<String?> = expenseDetailCallBacks.getUserFullName(userUuid)
}

data class ExpenseScreenState(
    val isLoading: Boolean = false,
    val expenseWithBeneficiaries: ExpenseWithBeneficiaries? = null, // TODO.
)
