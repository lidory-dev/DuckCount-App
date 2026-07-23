package app.lidory.duckcount.expenseList.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.expenseList.callBacks.ExpenseDetailCallBacks
import app.lidory.duckcount.expenseList.model.BeneficiaryUi
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.model.ExpenseWriteError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseDetailViewModel(
    private val expenseUuid: String,
    private val expenseDetailCallBacks: ExpenseDetailCallBacks,
) : ViewModel() {

    private val _navigateBack = Channel<Unit>(Channel.BUFFERED)
    val navigateBack: Flow<Unit> = _navigateBack.receiveAsFlow()

    // Emitted when the delete failed (offline / server error) so the screen can surface it.
    private val _showError = Channel<ExpenseWriteError>(Channel.BUFFERED)
    val showError: Flow<ExpenseWriteError> = _showError.receiveAsFlow()

    private val authenticatedUserUuid: String? = expenseDetailCallBacks.getAuthenticatedUserUuid()

    private val expense: StateFlow<ExpenseWithBeneficiaries?> =
        expenseDetailCallBacks.getExpenseWithBeneficiaries(uuid = expenseUuid)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = null,
            )

    private val creditorName: Flow<String> =
        expense
            .map { it?.creditorUuid }
            .distinctUntilChanged()
            .flatMapLatest { uuid ->
                if (uuid == null) flowOf("")
                else expenseDetailCallBacks.getUserFullName(uuid).map { it ?: "" }
            }

    private val beneficiaryNames: Flow<Map<String, String?>> =
        expense
            .map { it?.beneficiaries?.map { beneficiary -> beneficiary.userUuid } ?: emptyList() }
            .distinctUntilChanged()
            .flatMapLatest { userUuids ->
                if (userUuids.isEmpty()) flowOf(emptyMap())
                else combine(
                    userUuids.map { uuid ->
                        expenseDetailCallBacks.getUserFullName(uuid).map { name -> uuid to name }
                    },
                ) { pairs -> pairs.toMap() }
            }

    private val beneficiaries: Flow<List<BeneficiaryUi>> =
        combine(
            expense.map { it?.beneficiaries.orEmpty() }.distinctUntilChanged(),
            beneficiaryNames,
        ) { list, names ->
            list.map { beneficiary ->
                BeneficiaryUi(
                    uuid = beneficiary.uuid,
                    userUuid = beneficiary.userUuid,
                    share = beneficiary.share,
                    fullName = names[beneficiary.userUuid] ?: "",
                    isShareFixed = beneficiary.isShareFixed,
                )
            }
        }

    val state: StateFlow<ExpenseScreenState> =
        combine(expense, creditorName, beneficiaries) { expenseValue, creditor, beneficiaryUis ->
            if (expenseValue == null) {
                ExpenseScreenState.Loading
            } else {
                ExpenseScreenState.Content(
                    expenseWithBeneficiaries = expenseValue,
                    creditorFullName = creditor,
                    beneficiaries = beneficiaryUis,
                    // A settlement may only be deleted by its beneficiary (the money's recipient).
                    // A one-time expense may only be deleted by its creditor.
                    // A group expense may be deleted by any member of the group.
                    canDelete = (expenseValue.isSettlement && expenseValue.beneficiaries.any { it.userUuid == authenticatedUserUuid })
                            || (expenseValue.groupUuid == null && expenseValue.creditorUuid == authenticatedUserUuid)
                            || expenseValue.groupUuid != null,
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = ExpenseScreenState.Loading,
        )

    fun deleteExpense() {
        viewModelScope.launch {
            val error = expenseDetailCallBacks.deleteExpense(expenseUuid)
            if (error == null) _navigateBack.send(Unit) else _showError.send(error)
        }
    }

    fun restoreExpense() {
        viewModelScope.launch {
            expenseDetailCallBacks.restoreExpense(expenseUuid)
            _navigateBack.send(Unit)
        }
    }

    fun getUserFullName(userUuid: String): Flow<String?> = expenseDetailCallBacks.getUserFullName(userUuid)
}

sealed interface ExpenseScreenState {
    data object Loading : ExpenseScreenState
    data object NotFound : ExpenseScreenState
    data class Content(
        val expenseWithBeneficiaries: ExpenseWithBeneficiaries,
        val creditorFullName: String,
        val beneficiaries: List<BeneficiaryUi>,
        val canDelete: Boolean,
    ) : ExpenseScreenState
}
