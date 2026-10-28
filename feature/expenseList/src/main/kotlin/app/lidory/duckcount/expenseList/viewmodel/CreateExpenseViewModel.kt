package app.lidory.duckcount.expenseList.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.expenseList.R
import app.lidory.duckcount.expenseList.callBacks.CreateExpenseCallBacks
import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.BeneficiaryUi
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.ExpenseSaveError
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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
    private val _showError = Channel<ExpenseSaveError>(Channel.BUFFERED)
    val showError: Flow<ExpenseSaveError> = _showError.receiveAsFlow()

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

        val hasSubunits = state.currency.hasSubunits
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

    // Snapshot state the screen reads as it is, rather than a flow: the text each field of the form
    // edits has to come back to it in the same frame as the edit. What is worked out from the form
    // as a flow reads it with snapshotFlow, and may follow a frame later.
    var state by mutableStateOf(
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
        private set

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
        combine(snapshotFlow { state }, loadedState) { current, loaded ->
            loaded != null && current.savedContent() != loaded.savedContent()
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

    /** The name the form shows for each of its beneficiaries, by their user uuid, as it is read. */
    val beneficiaryNames: StateFlow<Map<String, String>> =
        snapshotFlow { state.beneficiaries.map { it.userUuid } }
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
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = emptyMap(),
            )

    /**
     * The beneficiaries as the form lists them, named as [names] has them, see [beneficiaryNames].
     * Taken from [state] as it is, and not through a flow, since each row's share is a field's text.
     */
    fun displayedBeneficiaries(names: Map<String, String>): List<BeneficiaryUi> =
        state.beneficiaries.map { beneficiary ->
            BeneficiaryUi(
                uuid = beneficiary.uuid,
                userUuid = beneficiary.userUuid,
                share = beneficiary.share,
                fullName = names[beneficiary.userUuid] ?: beneficiary.userUuid,
                isShareFixed = beneficiary.isShareFixed,
            )
        }

    init {
        creditorNames(
            creditorUuids = snapshotFlow { state.creditorUuid },
            nameOf = createExpenseCallBacks::getUserFullName,
        ).onEach { updateCreditorFullName(it) }.launchIn(viewModelScope)

        // Once, and not whenever the form is collected again: coming back to the app after a
        // few seconds away would otherwise read the expense back over what the user typed.
        viewModelScope.launch {
            updateCreditor(creditorUuid = createExpenseCallBacks.getAuthenticatedUserUuid() ?: "")

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
                state = state.copy(timestamp = expense.timestamp)
                updateBeneficiaries(expense.beneficiaries)
                expense.beneficiaries.forEach {
                    updateBeneficiaryShare(it.userUuid, it.share)
                }

                loadedState.value = state
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
                    title = state.title,
                    description = state.description,
                    amount = state.amount,
                    creditorUuid = state.creditorUuid,
                    isSettlement = state.isSettlement,
                    currency = state.currency,
                    groupUuid = groupUuid,
                    beneficiaries = state.beneficiaries.map {
                        it.copy(expenseUuid = expenseUuid)
                    },
                    isDeleted = state.isDeleted,
                ),
            )

            if (error == null) _isSaved.value = true else report(error)
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
            _showError.send(ExpenseSaveError.Write(ExpenseWriteError.UNKNOWN))
            return@launch
        }

        _isSaving.value = true

        val saved = state

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

            if (error == null) _isSaved.value = true else report(error)
        } finally {
            _isSaving.value = false
        }
    }

    /**
     * Tells the screen why the write failed. When it was for naming people who deleted their
     * account, they are taken out of the selection first and the shares worked out again without
     * them, see [withoutDeletedAccounts], so that the user can save again.
     */
    private suspend fun report(error: ExpenseSaveError) {
        if (error is ExpenseSaveError.BeneficiariesDeleted) {
            onBeneficiariesSelected(
                uuids = withoutDeletedAccounts(
                    beneficiaryUuids = state.beneficiaries.map { it.userUuid },
                    deletedUserUuids = error.beneficiaries.map { it.userUuid },
                ),
            )
        }

        _showError.send(error)
    }

    fun updateTitle(title: String) {
        state = state.copy(title = title)
    }

    fun updateAmount(amount: String) {
        val newAmount = normalizeAmountInput(raw = amount, previous = state.amount) ?: return
        state = state.copy(amount = newAmount)
    }

    fun updateCurrency(currency: Currency) {
        val reformat: (String) -> String = { value ->
            when {
                value.isBlank() -> value
                currency.hasSubunits -> value.roundAmountWithSubunits()
                else -> value.roundAmountWithoutSubunits()
            }
        }

        state = state.copy(
            currency = currency,
            amount = reformat(state.amount),
            beneficiaries = state.beneficiaries.map { it.copy(share = reformat(it.share)) },
        )
    }

    fun updateDescription(description: String) {
        state = state.copy(description = description)
    }

    /** Makes [creditorUuid] the creditor, whose name follows, see [creditorNames]. */
    fun updateCreditor(creditorUuid: String) {
        state = state.copy(creditorUuid = creditorUuid)
    }

    fun updateCreditorFullName(creditorFullName: String) {
        state = state.copy(creditorFullName = creditorFullName)
    }

    fun updateBeneficiaries(beneficiaries: List<Beneficiary>) {
        state = state.copy(beneficiaries = beneficiaries)
        _selectedUuids.update { state.beneficiaries.map { it.userUuid }.toSet() }
    }

    fun updateBeneficiaryShare(userUuid: String, share: String) {
        val previous = state.beneficiaries
            .firstOrNull { it.userUuid == userUuid }
            ?.share
            ?: return
        val newShare = normalizeAmountInput(share, previous) ?: return

        state = state.copy(
            beneficiaries = state.beneficiaries.map { beneficiary ->
                if (beneficiary.userUuid == userUuid) {
                    beneficiary.copy(share = newShare)
                } else {
                    beneficiary
                }
            },
        )
    }

    fun isNewExpense() = _expenseUuid.value == null

    fun removeBeneficiaryFixedShare(userUuid: String) {
        updateBeneficiaries(
            state.beneficiaries.map { beneficiary ->
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
        val fixedBeneficiaries = state.beneficiaries.filter { it.isShareFixed }
        val fixedShareUuids = fixedBeneficiaries.mapTo(mutableSetOf()) { it.userUuid }

        val beneficiaries = computeBeneficiaries(
            amount = state.amount.ifBlank { "0" },
            currency = state.currency,
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
            amount = state.amount.toValidNumber(),
            currency = state.currency,
            beneficiaryUserUuids = state.beneficiaries.map { it.userUuid },
            fixedUuid = state.beneficiaries
                .filter { it.isShareFixed }
                .associate { it.userUuid to (it.share.toValidNumber()) },
        )

        beneficiaries.forEach {
            updateBeneficiaryShare(it.userUuid, it.share)
        }
    }

    fun setBeneficiaryFixedShare(userUuid: String) {
        state = state.copy(
            beneficiaries = state.beneficiaries.map { beneficiary ->
                if (beneficiary.userUuid == userUuid)
                    beneficiary.copy(isShareFixed = true)
                else
                    beneficiary
            },
        )
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
 * The beneficiaries who stay selected once the server refused [deletedUserUuids] for having
 * deleted their account: everybody else in [beneficiaryUuids], in the same order.
 *
 * Only the ones it names go. On an edit, it names only those the edit adds: one the expense
 * named already stays on it, whether or not they deleted their account since.
 */
fun withoutDeletedAccounts(
    beneficiaryUuids: List<String>,
    deletedUserUuids: List<String>,
): Set<String> = beneficiaryUuids.toSet() - deletedUserUuids.toSet()

/**
 * Emits the name the form shows for the creditor, the last of [creditorUuids] picked, as [nameOf]
 * tells it, each time it changes: a member of the group who is not the user's contact is named
 * once a group sync reads their profile, which can be with the form open. Read for the creditor
 * picked last alone, so that the name of one picked before never comes back over theirs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun creditorNames(
    creditorUuids: Flow<String>,
    nameOf: (userUuid: String) -> Flow<String?>,
): Flow<String> =
    creditorUuids.distinctUntilChanged().flatMapLatest { creditorUuid ->
        // TODO: Show that the username was not found?
        nameOf(creditorUuid).map { it ?: creditorUuid }
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
