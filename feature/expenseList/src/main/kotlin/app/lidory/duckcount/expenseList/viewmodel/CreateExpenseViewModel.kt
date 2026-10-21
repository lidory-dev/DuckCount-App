package app.lidory.duckcount.expenseList.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.expenseList.R
import app.lidory.duckcount.expenseList.callBacks.CreateExpenseCallBacks
import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.BeneficiaryUi
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.model.ExpenseWriteError
import app.lidory.duckcount.expenseList.model.User
import app.lidory.duckcount.expenseList.utils.formatAmountWithSubunits
import app.lidory.duckcount.expenseList.utils.getCurrentTimestamp
import app.lidory.duckcount.expenseList.utils.roundAmountWithSubunits
import app.lidory.duckcount.expenseList.utils.roundAmountWithoutSubunits
import app.lidory.duckcount.expenseList.utils.toValidNumber
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class CreateExpenseViewModel(
    private val groupUuid: String? = null,
    private val expenseUuid: String? = null,
    private val createExpenseCallBacks: CreateExpenseCallBacks,
) : ViewModel() {

    private val _expenseUuid = MutableStateFlow(expenseUuid)
    val maxTitleLength: Int = createExpenseCallBacks.maxTitleLength
    val maxDescriptionLength: Int = createExpenseCallBacks.maxDescriptionLength

    /** The most beneficiaries the selection may hold: see [CreateExpenseCallBacks.maxBeneficiaries]. */
    val maxBeneficiaries: Int = createExpenseCallBacks.maxBeneficiaries

    // True once the create/update write has actually completed. The screen must wait
    // for this before navigating: navigating away clears this ViewModel and cancels
    // viewModelScope, which would otherwise abort the in-flight write. A state rather
    // than an event: the write can complete while the app is in the background, where
    // the screen cannot navigate, and the screen then closes once the user is back.
    private val _isSaved = MutableStateFlow(false)
    val isSaved: StateFlow<Boolean> = _isSaved.asStateFlow()

    // Emitted with the reason when the write failed, so the screen can surface a
    // message that matches the actual cause instead of silently dropping the expense.
    private val _showError = Channel<ExpenseWriteError>(Channel.BUFFERED)
    val showError: Flow<ExpenseWriteError> = _showError.receiveAsFlow()

    // True while a create/update write is in flight, so the confirm button can show
    // an in-place spinner and stay disabled until the write completes.
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    var isCreditorDialogShown by mutableStateOf(false)

    var isBeneficiariesDialogShown by mutableStateOf(false)

    val currencies: List<Currency> = createExpenseCallBacks.getCurrencies()

    /** Localized display name for an ISO 4217 currency code (e.g. "USD" -> "US Dollar"). */
    fun currencyDisplayName(code: String): String = createExpenseCallBacks.getCurrencyDisplayName(code)

    private fun normalizeAmountInput(raw: String, previous: String): String? {
        val formatted = raw.formatAmountWithSubunits()
        if (!formatted.matches(Regex("""^\d*\.?\d*$"""))) return null

        val hasSubunits = _state.value.currency.hasSubunits
        val rounded = when {
            formatted.isBlank() -> formatted
            hasSubunits -> formatted.roundAmountWithSubunits()
            else -> formatted.roundAmountWithoutSubunits()
        }

        val droppedDecimalSeparator =
            hasSubunits
                    && previous.contains('.')
                    && !rounded.contains('.')
                    && previous.replace(".", "") == rounded

        return if (droppedDecimalSeparator) rounded.dropLast(2) else rounded
    }

    fun showCreditorDialog() {
        isCreditorDialogShown = true
    }

    fun hideCreditorDialog() {
        isCreditorDialogShown = false
    }

    fun showBeneficiariesDialog() {
        isBeneficiariesDialogShown = true
    }

    fun hideBeneficiariesDialog() {
        isBeneficiariesDialogShown = false
    }

    private val _state = MutableStateFlow(
        CreateExpenseState(
            currency = createExpenseCallBacks.getDefaultCurrency(),
            validateExpenseCallBack = { amount, currency, beneficiaries ->
                createExpenseCallBacks.validateExpense(
                    amount = amount,
                    currency = currency,
                    beneficiaries = beneficiaries,
                )
            },
        ),
    )
    val state: StateFlow<CreateExpenseState> = _state.asStateFlow()

    /**
     * The form as it stood once the expense being edited was read into it: what an edit has to
     * differ from for there to be anything to save. Null when creating, and until it is read.
     */
    private val loadedState = MutableStateFlow<CreateExpenseState?>(null)

    /**
     * Whether the expense being edited has changed since it was read. Saved unchanged, it would
     * spend one of the few edits the server allows a one-time expense, and add a version to its
     * history that says nothing new.
     */
    val hasChanges: StateFlow<Boolean> =
        combine(_state, loadedState) { state, loaded ->
            loaded != null && state.savedContent() != loaded.savedContent()
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = false,
        )

    private var _searchQuery = MutableStateFlow("")
    var searchQuery by mutableStateOf("")
        private set

    val usersState: StateFlow<UserListState> = _searchQuery
        .flatMapLatest { query ->
            if (groupUuid != null) {
                createExpenseCallBacks.getAllUsersFromGroupFiltered(filter = query, groupUuid = groupUuid)
            } else {
                createExpenseCallBacks.getAllUsersFiltered(filter = query)
            }
        }
        .map { users ->
            UserListState(users)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = UserListState(),
        )

    private val _selectedUuids = MutableStateFlow<Set<String>>(emptySet())
    val selectedUuids: StateFlow<Set<String>> = _selectedUuids

    val selectionToggleState: StateFlow<ToggleableState> = combine(
        _selectedUuids,
        usersState,
    ) { selectedUuids, users ->
        toggleableState(selectedUuids = selectedUuids, totalUsers = users.users.size)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = ToggleableState.Off,
    )

    private val beneficiaryNames: Flow<Map<String, String>> =
        _state
            .map { state -> state.beneficiaries.map { it.userUuid } }
            .distinctUntilChanged()
            .flatMapLatest { userUuids ->
                if (userUuids.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(
                        userUuids.map { uuid ->
                            createExpenseCallBacks.getUserFullName(uuid).map { name ->
                                // TODO: Show that the username was not found?
                                name?.let { uuid to name } ?: (uuid to uuid)
                            }
                        },
                    ) { pairs -> pairs.toMap() }
                }
            }

    val displayedBeneficiaries: StateFlow<List<BeneficiaryUi>> =
        combine(
            _state.map { it.beneficiaries }.distinctUntilChanged(),
            beneficiaryNames,
        ) { beneficiaries, names ->
            beneficiaries.map { beneficiary ->
                BeneficiaryUi(
                    uuid = beneficiary.uuid,
                    userUuid = beneficiary.userUuid,
                    share = beneficiary.share,
                    fullName = names[beneficiary.userUuid] ?: beneficiary.userUuid,
                    isShareFixed = beneficiary.isShareFixed,
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList(),
        )

    init {
        // Once, and not whenever the form is collected again: coming back to the app after a
        // few seconds away would otherwise read the expense back over what the user typed.
        viewModelScope.launch {
            updateCreditor(creditorUuid = createExpenseCallBacks.getAuthenticatedUserUuid() ?: "")

            getCreditorName()

            groupUuid?.let {
                val groupCurrency = createExpenseCallBacks.getGroupCurrency(groupUuid = it).first()
                updateCurrency(groupCurrency)
            }

            expenseUuid?.let { uuid ->
                val expense = createExpenseCallBacks.getExpenseWithBeneficiaries(expenseUuid = uuid).first()

                // The currency first: it decides how the amount and the shares are written.
                updateCurrency(expense.currency)
                updateTitle(expense.title)
                updateCreditor(expense.creditorUuid)
                updateAmount(expense.amount)
                updateDescription(expense.description)
                _state.update { it.copy(timestamp = expense.timestamp) }
                updateBeneficiaries(expense.beneficiaries)
                expense.beneficiaries.forEach {
                    updateBeneficiaryShare(it.userUuid, it.share)
                }

                loadedState.value = _state.value
            }
        }
    }

    fun toggleSelectedUser(user: User) {
        _selectedUuids.update { selected ->
            toggledSelection(selected = selected, uuid = user.uuid, max = maxBeneficiaries)
        }
    }

    /**
     * Selects every member of the group, or nobody once they all are. Offered for a group only,
     * see [isOneTimeExpense], whose members are to number no more than [maxBeneficiaries], so it
     * does not count them against it.
     */
    fun toggleSelectAllUsers() {
        val allUuids = usersState.value.users.mapTo(mutableSetOf()) { it.uuid }
        _selectedUuids.update { selected ->
            if (selected == allUuids) emptySet() else allUuids
        }
    }

    fun onSearchQueryChange(query: String) {
        searchQuery = query
        _searchQuery.value = query
    }

    fun clearSearchQuery() {
        searchQuery = ""
        _searchQuery.value = ""
    }


    @OptIn(ExperimentalUuidApi::class)
    fun createExpense() = viewModelScope.launch {
        // Not once saved either: each attempt is an expense of its own, so a second one would
        // add the same expense twice.
        if (_isSaving.value || _isSaved.value) return@launch

        _isSaving.value = true

        try {
            val expenseUuid = Uuid.random().toString()

            val error = createExpenseCallBacks.insertWithBeneficiaries(
                expenseWithBeneficiaries = ExpenseWithBeneficiaries(
                    // TODO: get uuid from server
                    uuid = expenseUuid,
                    timestamp = getCurrentTimestamp(),
                    title = _state.value.title,
                    description = _state.value.description,
                    amount = _state.value.amount,
                    creditorUuid = _state.value.creditorUuid,
                    isSettlement = _state.value.isSettlement,
                    currency = _state.value.currency,
                    groupUuid = groupUuid,
                    beneficiaries = _state.value.beneficiaries.map {
                        it.copy(expenseUuid = expenseUuid)
                    },
                    isDeleted = _state.value.isDeleted,
                ),
            )

            if (error == null) _isSaved.value = true else _showError.send(error)
        } finally {
            _isSaving.value = false
        }
    }

    fun updateExpense() = viewModelScope.launch {
        // Not once saved either: saving it again would spend another of the few edits the
        // server allows a one-time expense, on a version that says nothing new.
        if (_isSaving.value || _isSaved.value) return@launch

        // Editing an existing expense always has a uuid; guard the impossible null case.
        val expenseUuid = expenseUuid
        if (expenseUuid == null) {
            _showError.send(ExpenseWriteError.UNKNOWN)
            return@launch
        }

        _isSaving.value = true

        val saved = _state.value

        try {
            val error = createExpenseCallBacks.updateExpenseWithBeneficiaries(
                expenseWithBeneficiaries = ExpenseWithBeneficiaries(
                    uuid = expenseUuid,
                    // The date it was created with: an edit changes what the expense says, not
                    // when it happened, and its history tells when each version was made.
                    timestamp = saved.timestamp,
                    title = saved.title,
                    description = saved.description,
                    amount = saved.amount,
                    creditorUuid = saved.creditorUuid,
                    isSettlement = saved.isSettlement,
                    currency = saved.currency,
                    groupUuid = groupUuid,
                    beneficiaries = saved.beneficiaries.map {
                        it.copy(expenseUuid = expenseUuid)
                    },
                    isDeleted = saved.isDeleted,
                ),
            )

            if (error == null) _isSaved.value = true else _showError.send(error)
        } finally {
            _isSaving.value = false
        }
    }

    fun getCreditorName() {
        viewModelScope.launch {
            createExpenseCallBacks.getUserFullName(_state.value.creditorUuid)
                .collect {
                    // TODO: Show that the username was not found?
                    val fullName = it ?: _state.value.creditorUuid
                    updateCreditorFullName(fullName)
                }
        }
    }

    fun updateTitle(title: String) {
        _state.update {
            it.copy(title = title)
        }
    }

    fun updateAmount(amount: String) {
        val newAmount = normalizeAmountInput(raw = amount, previous = _state.value.amount) ?: return
        _state.update { it.copy(amount = newAmount) }
    }

    fun updateCurrency(currency: Currency) {
        _state.update { state ->
            val reformat: (String) -> String = { value ->
                when {
                    value.isBlank() -> value
                    currency.hasSubunits -> value.roundAmountWithSubunits()
                    else -> value.roundAmountWithoutSubunits()
                }
            }

            state.copy(
                currency = currency,
                amount = reformat(state.amount),
                beneficiaries = state.beneficiaries.map { it.copy(share = reformat(it.share)) },
            )
        }
    }

    fun updateDescription(description: String) {
        _state.update {
            it.copy(description = description)
        }
    }

    fun updateCreditor(creditorUuid: String) {
        _state.update {
            it.copy(creditorUuid = creditorUuid)
        }
        getCreditorName()
    }

    fun updateCreditorFullName(creditorFullName: String) {
        _state.update {
            it.copy(creditorFullName = creditorFullName)
        }
    }

    fun updateBeneficiaries(beneficiaries: List<Beneficiary>) {
        _state.update { state ->
            state.copy(
                beneficiaries = beneficiaries,
            )
        }
        _selectedUuids.update { _state.value.beneficiaries.map { it.userUuid }.toSet() }
    }

    fun updateBeneficiaryShare(userUuid: String, share: String) {
        val previous = _state.value.beneficiaries
            .firstOrNull { it.userUuid == userUuid }
            ?.share
            ?: return
        val newShare = normalizeAmountInput(share, previous) ?: return

        _state.update { state ->
            state.copy(
                beneficiaries = state.beneficiaries.map { beneficiary ->
                    if (beneficiary.userUuid == userUuid) {
                        beneficiary.copy(share = newShare)
                    } else {
                        beneficiary
                    }
                },
            )
        }
    }

    fun isNewExpense() = _expenseUuid.value == null

    fun removeBeneficiaryFixedShare(userUuid: String) {
        updateBeneficiaries(
            _state.value.beneficiaries.map { beneficiary ->
                if (beneficiary.userUuid == userUuid) {
                    beneficiary.copy(isShareFixed = false)
                } else {
                    beneficiary
                }
            },
        )

        calculateShares()
    }

    fun onBeneficiariesSelected(uuids: Set<String>) {
        val fixedBeneficiaries = _state.value.beneficiaries.filter { it.isShareFixed }
        val fixedShareUuids = fixedBeneficiaries.mapTo(mutableSetOf()) { it.userUuid }

        val beneficiaries = computeBeneficiaries(
            amount = _state.value.amount.ifBlank { "0" },
            currency = _state.value.currency,
            beneficiaryUserUuids = uuids.toList(),
            fixedUuid = fixedBeneficiaries
                .filter { it.userUuid in uuids }
                .associate { it.userUuid to it.share },
        )

        updateBeneficiaries(
            beneficiaries.map { beneficiary ->
                if (beneficiary.userUuid in fixedShareUuids) beneficiary.copy(isShareFixed = true) else beneficiary
            },
        )
    }

    fun computeBeneficiaries(
        amount: String,
        currency: Currency,
        beneficiaryUserUuids: List<String>,
        fixedUuid: Map<String, String>,
    ): List<Beneficiary> {
        return createExpenseCallBacks.computeBeneficiaries(
            amount = amount.toValidNumber(),
            currency = currency,
            beneficiaryUserUuids = beneficiaryUserUuids,
            fixedUuid = fixedUuid,
            expenseUuid = _expenseUuid.value ?: "",
        ).map { beneficiary ->
            if (beneficiary.userUuid in fixedUuid)
                beneficiary.copy(isShareFixed = true)
            else
                beneficiary
        }
    }

    fun calculateShares() {
        val beneficiaries = computeBeneficiaries(
            amount = _state.value.amount.toValidNumber(),
            currency = _state.value.currency,
            beneficiaryUserUuids = _state.value.beneficiaries.map { it.userUuid },
            fixedUuid = _state.value.beneficiaries
                .filter { it.isShareFixed }
                .associate { it.userUuid to (it.share.toValidNumber()) },
        )

        beneficiaries.forEach {
            updateBeneficiaryShare(it.userUuid, it.share)
        }
    }

    fun setBeneficiaryFixedShare(userUuid: String) {
        _state.update {
            it.copy(
                beneficiaries = it.beneficiaries.map { beneficiary ->
                    if (beneficiary.userUuid == userUuid)
                        beneficiary.copy(isShareFixed = true)
                    else
                        beneficiary
                },
            )
        }
    }

    fun getExpenseGroupUuid() = groupUuid

    fun isOneTimeExpense(): Boolean = groupUuid == null
}

data class CreateExpenseState(
    val timestamp: String = "",
    val title: String = "",
    val description: String = "",
    val amount: String = "",
    val creditorUuid: String = "",
    val creditorFullName: String = "",
    val isSettlement: Boolean = false,
    val currency: Currency,
    val beneficiaries: List<Beneficiary> = emptyList(),
    val isDeleted: Boolean = false,
    private val validateExpenseCallBack: (String, Currency, List<Beneficiary>) -> Int?,
) {
    val error: Int? =
        when {
            // The amount field says so itself, and nothing is saved without one.
            amount.isBlank() -> null

            (beneficiaries.size == 1 && !beneficiaries.any { it.userUuid != creditorUuid })
                -> R.string.no_beneficiary_other_than_the_creditor

            beneficiaries.any { it.share.isBlank() } -> R.string.non_positive_amount_share
            else -> validateExpenseCallBack(amount, currency, beneficiaries)
        }
}

/**
 * What saving the form writes of the expense, and so what an edit has to change. Each share is
 * taken by whose it is: working the shares out again gives every beneficiary a new identifier,
 * which changes nothing.
 */
private fun CreateExpenseState.savedContent(): List<Any> =
    listOf(
        title,
        description,
        amount,
        currency,
        creditorUuid,
        beneficiaries.mapTo(HashSet()) { Triple(it.userUuid, it.share, it.isShareFixed) },
    )

data class UserListState(
    val users: List<User> = emptyList(),
)

fun toggleableState(
    selectedUuids: Set<String>,
    totalUsers: Int,
): ToggleableState = when {
    totalUsers > 0 && selectedUuids.size == totalUsers -> ToggleableState.On
    selectedUuids.isNotEmpty() -> ToggleableState.Indeterminate
    else -> ToggleableState.Off
}

/**
 * [selected] with [uuid] taken out, or added when that keeps it within [max]: past the limit,
 * selecting one more does nothing, and the screen shows that card disabled.
 */
fun toggledSelection(
    selected: Set<String>,
    uuid: String,
    max: Int,
): Set<String> = when {
    uuid in selected -> selected - uuid
    selected.size < max -> selected + uuid
    else -> selected
}
