package app.lidory.duckcount.expenseList.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.android.ui.text.sortedByName
import app.lidory.duckcount.expenseList.callBacks.ExpenseDetailCallBacks
import app.lidory.duckcount.expenseList.model.BeneficiaryUi
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.model.ExpenseWriteError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // True once the expense was deleted or restored, which leaves the screen nothing to show.
    // A state rather than an event: the write can complete while the app is in the background,
    // where the screen cannot navigate, and the screen then closes once the user is back.
    private val _isClosed = MutableStateFlow(false)
    val isClosed: StateFlow<Boolean> = _isClosed.asStateFlow()

    // Emitted when delete failed (offline / server error) so the screen can surface it.
    private val _showError = Channel<ExpenseWriteError>(Channel.BUFFERED)
    val showError: Flow<ExpenseWriteError> = _showError.receiveAsFlow()

    // True while marking the expense as paid back, or no longer, so that the button can show
    // an in-place spinner and stay disabled until the write completes.
    private val _isSettling = MutableStateFlow(false)
    val isSettling: StateFlow<Boolean> = _isSettling.asStateFlow()

    private val authenticatedUserUuid: String? = expenseDetailCallBacks.getAuthenticatedUserUuid()

    private val expense: StateFlow<ExpenseWithBeneficiaries?> =
        expenseDetailCallBacks.getExpenseWithBeneficiaries(uuid = expenseUuid)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = null,
            )

    /**
     * Null when this device holds no profile for them, which a one-time expense makes
     * ordinary: the server asks for no relationship between a creditor and the people they
     * name, and a contact who was removed keeps their expenses. The screen has the strings,
     * so what to show in their place is decided there.
     */
    private val creditorName: Flow<String?> =
        expense
            .map { it?.creditorUuid }
            .distinctUntilChanged()
            .flatMapLatest { uuid ->
                if (uuid == null) flowOf(null)
                else expenseDetailCallBacks.getUserFullName(uuid)
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

    // The user first, then the others by name, see sortedByName.
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
                    fullName = names[beneficiary.userUuid],
                    isShareFixed = beneficiary.isShareFixed,
                )
            }.sortedByName(selfUuid = authenticatedUserUuid, uuidOf = { it.userUuid }, nameOf = { it.fullName })
        }

    // A group the user is no longer a member of, removed or left, stays read-only, its expenses
    // with it, and a sync can read the removal with the screen open.
    private val isGroupLeft: Flow<Boolean> =
        expense
            .map { it?.groupUuid }
            .distinctUntilChanged()
            .flatMapLatest { uuid ->
                if (uuid == null) flowOf(false)
                else expenseDetailCallBacks.isGroupLeft(uuid)
            }

    val state: StateFlow<ExpenseScreenState> =
        combine(expense, creditorName, beneficiaries, isGroupLeft) { expenseValue, creditor, beneficiaryUis, isLeft ->
            if (expenseValue == null) {
                ExpenseScreenState.Loading
            } else {
                val actions = expenseValue.actions(selfUuid = authenticatedUserUuid, isGroupLeft = isLeft)

                ExpenseScreenState.Content(
                    expenseWithBeneficiaries = expenseValue,
                    creditorFullName = creditor,
                    beneficiaries = beneficiaryUis,
                    canEdit = actions.canEdit,
                    canDelete = actions.canDelete,
                    canRestore = actions.canRestore,
                    canSettle = actions.canSettle,
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = ExpenseScreenState.Loading,
        )

    fun deleteExpense() {
        viewModelScope.launch {
            // Not once closed: the expense is already deleted, or was just restored.
            if (_isClosed.value) return@launch

            val error = expenseDetailCallBacks.deleteExpense(expenseUuid)
            if (error == null) _isClosed.value = true else _showError.send(error)
        }
    }

    /** Marks the expense as paid back, or no longer, as it is shown now. */
    fun toggleSettled() {
        val shown = expense.value ?: return

        viewModelScope.launch {
            if (_isSettling.value) return@launch

            _isSettling.value = true

            try {
                expenseDetailCallBacks.setSettled(expenseUuid = expenseUuid, isSettled = !shown.isSettled)
                    ?.let { _showError.send(it) }
            } finally {
                _isSettling.value = false
            }
        }
    }

    fun restoreExpense() {
        viewModelScope.launch {
            // Not once closed: a deleted expense shows the restore button, and restoring it
            // would undo the deletion the screen is closing on.
            if (_isClosed.value) return@launch

            expenseDetailCallBacks.restoreExpense(expenseUuid)
            _isClosed.value = true
        }
    }
}

sealed interface ExpenseScreenState {
    data object Loading : ExpenseScreenState
    data object NotFound : ExpenseScreenState
    data class Content(
        val expenseWithBeneficiaries: ExpenseWithBeneficiaries,
        val creditorFullName: String?,
        val beneficiaries: List<BeneficiaryUi>,
        val canEdit: Boolean,
        val canDelete: Boolean,

        /** Whether the expense, once deleted, can be restored: not in a group the user is no longer in. */
        val canRestore: Boolean,
        val canSettle: Boolean,
    ) : ExpenseScreenState
}

/** What the user may do with an expense on its detail screen, see [actions]. */
data class ExpenseActions(
    val canEdit: Boolean,
    val canDelete: Boolean,

    /** Whether the expense, once deleted, can be restored. */
    val canRestore: Boolean,
    val canSettle: Boolean,
)

/**
 * What the user, [selfUuid], may do with this expense: nothing that changes it in a group they are
 * no longer a member of, removed or left, [isGroupLeft], which stays read-only, its expenses with
 * it.
 */
fun ExpenseWithBeneficiaries.actions(selfUuid: String?, isGroupLeft: Boolean) = ExpenseActions(
    canEdit = !isGroupLeft && (
        (!isSettlement && groupUuid != null)
            || (!isSettlement && creditorUuid == selfUuid)
    ),
    // A settlement may only be deleted by its beneficiary (the money's recipient).
    // A one-time expense may only be deleted by its creditor.
    // A group expense may be deleted by any member of the group, while they are one.
    canDelete = !isGroupLeft && (
        (isSettlement && beneficiaries.any { it.userUuid == selfUuid })
            || (groupUuid == null && creditorUuid == selfUuid)
            || groupUuid != null
    ),
    canRestore = !isGroupLeft,
    // Only a one-time expense can be marked as paid back, and only by its
    // creditor, the one paid back: they sign it, and the server takes the
    // mark from nobody else.
    canSettle = groupUuid == null && creditorUuid == selfUuid,
)
