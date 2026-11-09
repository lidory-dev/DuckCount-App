package app.lidory.duckcount.expenseList.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.android.ui.text.sortedByName
import app.lidory.duckcount.expenseList.R
import app.lidory.duckcount.expenseList.callBacks.CreateExpenseCallBacks
import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.BeneficiaryUi
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.ExpenseSaveError
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.model.ExpenseWriteError
import app.lidory.duckcount.expenseList.model.User
import app.lidory.duckcount.expenseList.utils.dropDecimalsIfSeparatorDeleted
import app.lidory.duckcount.expenseList.utils.getCurrentTimestamp
import app.lidory.duckcount.expenseList.utils.isAmountInput
import app.lidory.duckcount.expenseList.utils.roundAmountWithSubunits
import app.lidory.duckcount.expenseList.utils.roundAmountWithoutSubunits
import app.lidory.duckcount.expenseList.utils.toAmount
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

    /**
     * Whether an amount field, the expense's or a share's, takes [text] as typed, see
     * [isAmountInput]: the field refuses the edit otherwise, keeping its text and its cursor.
     */
    fun acceptsAmount(text: String): Boolean = text.isAmountInput(hasSubunits = state.currency.hasSubunits)

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
     * Whether the expense being edited has changed since it was read, see [isChangedFrom]. Saved
     * unchanged, it would spend one of the few edits the server allows a one-time expense, and add
     * a version to its history that says nothing new.
     */
    val hasChanges: StateFlow<Boolean> =
        combine(snapshotFlow { state }, loadedState) { current, loaded ->
            loaded != null && current.isChangedFrom(loaded)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = false,
        )

    // Read once it is first needed, to list the user first, see sortedByName.
    private val selfUuid: String? by lazy { createExpenseCallBacks.getAuthenticatedUserUuid() }

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
            UserListState(users.sortedByName(selfUuid = selfUuid, uuidOf = { it.uuid }, nameOf = { it.fullName }))
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

    /**
     * The name of each of the form's beneficiaries, by their user uuid, as it is read: null for
     * somebody this device holds no profile for, whom the rows list last, see displayedBeneficiaries.
     */
    val beneficiaryNames: StateFlow<Map<String, String?>> =
        snapshotFlow { state.beneficiaries.map { it.userUuid } }
            .flatMapLatest { userUuids ->
                if (userUuids.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(
                        userUuids.map { uuid ->
                            createExpenseCallBacks.getUserFullName(uuid).map { name -> uuid to name }
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
     * The beneficiaries as the form lists them, named as [names] has them, see [beneficiaryNames],
     * the user first and the others by name, see [sortedByName]. Taken from [state] as it is, and not
     * through a flow, since each row's share is a field's text. Ordered here alone, for the rows:
     * the expense keeps its beneficiaries in the order they were picked, which decides who the
     * library gives the cents that do not divide evenly to.
     */
    fun displayedBeneficiaries(names: Map<String, String?>): List<BeneficiaryUi> =
        state.beneficiaries
            .map { beneficiary ->
                BeneficiaryUi(
                    uuid = beneficiary.uuid,
                    userUuid = beneficiary.userUuid,
                    // As typed while its field is being typed in.
                    share = state.shareTexts[beneficiary.userUuid] ?: beneficiary.share,
                    // TODO: Show that the username was not found?
                    fullName = names[beneficiary.userUuid] ?: beneficiary.userUuid,
                    isShareFixed = beneficiary.isShareFixed,
                )
            }
            .sortedByName(selfUuid = selfUuid, uuidOf = { it.userUuid }, nameOf = { names[it.userUuid] })

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

            expenseUuid?.let { uuid -> read(expenseUuid = uuid) }
        }
    }

    /**
     * Reads the expense [expenseUuid] into the form, as this device shows it, the version an edit
     * is written after, and takes the form as it then stands for what the edit has to differ from,
     * see [loadedState].
     */
    private suspend fun read(expenseUuid: String) {
        val expense = createExpenseCallBacks.getExpenseWithBeneficiaries(expenseUuid = expenseUuid).first()

        // The currency first: it decides how the amount and the shares are written.
        updateCurrency(expense.currency)
        updateTitle(expense.title)
        updateCreditor(expense.creditorUuid)
        updateAmount(expense.amount)
        updateDescription(expense.description)
        state = state.copy(timestamp = expense.timestamp, revision = expense.revision, isDeleted = expense.isDeleted)
        updateBeneficiaries(expense.beneficiaries)
        expense.beneficiaries.forEach {
            updateBeneficiaryShare(it.userUuid, it.share)
        }

        loadedState.value = state
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
                    // Until it is written: either kind is then given a UUID derived from the user's,
                    // see CreateExpenseCallBacks.insertWithBeneficiaries.
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
            // Deleting the expense of a group is no edit of it, so the server takes an edit written
            // after a deletion: the form saves nothing over one this device shows by now, see
            // ExpenseSaveError.DeletedMeanwhile. Not for a one-time expense, never shown deleted,
            // and gone once it is, which reading would wait on for good.
            val isDeletedMeanwhile = groupUuid != null &&
                    createExpenseCallBacks.getExpenseWithBeneficiaries(expenseUuid = expenseUuid).first().isDeleted

            if (isDeletedMeanwhile) {
                state = state.copy(isDeleted = true)
                _showError.send(ExpenseSaveError.DeletedMeanwhile)
                return@launch
            }

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
                    revision = saved.revision,
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
     * them, see [withoutDeletedAccounts], so that the user can save again. When another member
     * changed the expense first, the form is read again from the version this device shows of it
     * now, over what the user typed, see [ExpenseWriteError.CHANGED_MEANWHILE]: the user sees what
     * they would write over, and saves from it, every save built on the version read before being
     * refused the same way; unless it now shows the expense deleted, which the form saves nothing
     * over, see [ExpenseSaveError.DeletedMeanwhile].
     */
    private suspend fun report(error: ExpenseSaveError) {
        val isChangedMeanwhile = error == ExpenseSaveError.Write(ExpenseWriteError.CHANGED_MEANWHILE)

        if (isChangedMeanwhile) {
            expenseUuid?.let { read(expenseUuid = it) }
        }

        if (error is ExpenseSaveError.BeneficiariesDeleted) {
            onBeneficiariesSelected(
                uuids = withoutDeletedAccounts(
                    beneficiaryUuids = state.beneficiaries.map { it.userUuid },
                    deletedUserUuids = error.beneficiaries.map { it.userUuid },
                ),
            )
        }

        _showError.send(if (isChangedMeanwhile && state.isDeleted) ExpenseSaveError.DeletedMeanwhile else error)
    }

    fun updateTitle(title: String) {
        state = state.copy(title = title)
    }

    /**
     * Takes [amount], the amount field's text, as typed: neither formatted nor rounded, which would
     * hand the keyboard a new text. The amount it stands for is what the form uses and saves, see
     * [toAmount], and the field shows it written so once the user is done with it, see
     * [finishAmount].
     */
    fun updateAmount(amount: String) {
        val typed = amount.dropDecimalsIfSeparatorDeleted(previous = state.amountText)
        if (!acceptsAmount(typed)) return

        state = state.copy(
            amountText = typed,
            amount = typed.toAmount(hasSubunits = state.currency.hasSubunits),
        )
    }

    /** Writes the amount field's text as the amount it stands for, once the field is left. */
    fun finishAmount() {
        state = state.copy(amountText = state.amount)
    }

    /**
     * The user picked [currency]: the form is written anew for it, see [updateCurrency], and the
     * shares worked out again from the amount as it now stands, which those rounded one by one need
     * not add up to: 120.50 split in two is two shares of 60.25, 60 each once rounded, of a total
     * rounded to 121.
     */
    fun selectCurrency(currency: Currency) {
        updateCurrency(currency)
        calculateShares()
    }

    fun updateCurrency(currency: Currency) {
        val reformat: (String) -> String = { value ->
            when {
                value.isBlank() -> value
                currency.hasSubunits -> value.roundAmountWithSubunits()
                else -> value.roundAmountWithoutSubunits()
            }
        }

        val amount = reformat(state.amount)

        state = state.copy(
            currency = currency,
            amount = amount,
            amountText = amount,
            beneficiaries = state.beneficiaries.map { it.copy(share = reformat(it.share)) },
            shareTexts = emptyMap(),
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
        val userUuids = beneficiaries.mapTo(HashSet()) { it.userUuid }

        state = state.copy(
            beneficiaries = beneficiaries,
            shareTexts = state.shareTexts.filterKeys { it in userUuids },
        )
        _selectedUuids.update { state.beneficiaries.map { it.userUuid }.toSet() }
    }

    /** Sets the share of [userUuid] to [share], as worked out or read back, written as the form keeps it. */
    fun updateBeneficiaryShare(userUuid: String, share: String) {
        if (state.beneficiaries.none { it.userUuid == userUuid }) return

        val written = share.toAmount(hasSubunits = state.currency.hasSubunits)

        state = state.copy(
            beneficiaries = state.beneficiaries.map { beneficiary ->
                if (beneficiary.userUuid == userUuid) {
                    beneficiary.copy(share = written)
                } else {
                    beneficiary
                }
            },
        )
    }

    /**
     * Takes [text], the share field of [userUuid] as typed, as [updateAmount] takes the amount's:
     * the field shows it as typed until it is left, see [finishBeneficiaryShare], while the share
     * it stands for is the one worked out and saved, whatever the shares worked out again from it
     * write there meanwhile.
     */
    fun typeBeneficiaryShare(userUuid: String, text: String) {
        val share = state.beneficiaries.firstOrNull { it.userUuid == userUuid }?.share ?: return
        val typed = text.dropDecimalsIfSeparatorDeleted(previous = state.shareTexts[userUuid] ?: share)
        if (!acceptsAmount(typed)) return

        state = state.copy(shareTexts = state.shareTexts + (userUuid to typed))
        updateBeneficiaryShare(userUuid = userUuid, share = typed)
    }

    /** Shows the share of [userUuid] as the form keeps it, once its field is left. */
    fun finishBeneficiaryShare(userUuid: String) {
        state = state.copy(shareTexts = state.shareTexts - userUuid)
    }

    fun isNewExpense() = _expenseUuid.value == null

    fun removeBeneficiaryFixedShare(userUuid: String) {
        // Worked out again from here on, so what was typed there no longer stands.
        finishBeneficiaryShare(userUuid = userUuid)

        updateBeneficiaries(
            state.beneficiaries.map { beneficiary ->
                if (beneficiary.userUuid == userUuid) {
                    beneficiary.copy(isShareFixed = false, sharePercentage = null, shareWeight = null)
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
        val selected = fixedBeneficiaries.filter { it.userUuid in uuids }

        val beneficiaries = computeBeneficiaries(
            amount = state.amount.ifBlank { "0" },
            currency = state.currency,
            beneficiaryUserUuids = uuids.toList(),
            fixedUuid = selected
                .filter { it.isFixedAmount() }
                .associate { it.userUuid to it.share },
            sharePercentages = selected.sharePercentages(),
            shareWeights = selected.shareWeightsSetByHand(),
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
        sharePercentages: Map<String, String>,
        shareWeights: Map<String, String>,
    ): List<Beneficiary> {
        return createExpenseCallBacks.computeBeneficiaries(
            amount = amount.toValidNumber(),
            currency = currency,
            beneficiaryUserUuids = beneficiaryUserUuids,
            fixedUuid = fixedUuid,
            sharePercentages = sharePercentages,
            shareWeights = shareWeights,
            expenseUuid = _expenseUuid.value ?: "",
        ).map { beneficiary ->
            if (beneficiary.userUuid in fixedUuid || beneficiary.userUuid in sharePercentages || beneficiary.userUuid in shareWeights)
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
                .filter { it.isFixedAmount() }
                .associate { it.userUuid to (it.share.toValidNumber()) },
            sharePercentages = state.beneficiaries.sharePercentages(),
            shareWeights = state.beneficiaries.shareWeightsSetByHand(),
        )

        beneficiaries.forEach {
            updateBeneficiaryShare(it.userUuid, it.share)
        }

        // The library states a weight for everybody sharing what is left once anybody has one, and
        // for nobody once nobody does.
        val weights = beneficiaries.associate { it.userUuid to it.shareWeight }

        state = state.copy(
            beneficiaries = state.beneficiaries.map { it.copy(shareWeight = weights[it.userUuid]) },
        )
    }

    /** Makes the share of [userUuid] the amount it is now: if it was a percentage or a weight, it is one no more. */
    fun setBeneficiaryFixedShare(userUuid: String) {
        state = state.copy(
            beneficiaries = state.beneficiaries.map { beneficiary ->
                if (beneficiary.userUuid == userUuid)
                    beneficiary.copy(isShareFixed = true, sharePercentage = null, shareWeight = null)
                else
                    beneficiary
            },
        )
    }

    /**
     * Makes the share of [userUuid] [percentage] percent of the amount, and works the shares out
     * again from it, whatever amount the amount becomes from here on. What is typed for a share no
     * longer stands, see [typeBeneficiaryShare]. A percentage the library cannot read leaves the
     * share blank, and the form unable to save, until a later one replaces it.
     */
    fun setBeneficiaryPercentage(userUuid: String, percentage: String) {
        finishBeneficiaryShare(userUuid = userUuid)

        state = state.copy(
            beneficiaries = state.beneficiaries.map { beneficiary ->
                if (beneficiary.userUuid == userUuid)
                    beneficiary.copy(isShareFixed = true, sharePercentage = percentage.trim(), shareWeight = null)
                else
                    beneficiary
            },
        )

        calculateShares()
    }

    /**
     * Makes the share of [userUuid] [weight] parts of what the fixed shares and percentages leave,
     * next to the parts of the others, who count for one until they are given theirs, and works the
     * shares out again from it. What is typed for a share no longer stands, see
     * [typeBeneficiaryShare]. A weight the library cannot read leaves the share blank, and the form
     * unable to save, until a later one replaces it.
     */
    fun setBeneficiaryWeight(userUuid: String, weight: String) {
        finishBeneficiaryShare(userUuid = userUuid)

        state = state.copy(
            beneficiaries = state.beneficiaries.map { beneficiary ->
                if (beneficiary.userUuid == userUuid)
                    beneficiary.copy(isShareFixed = true, sharePercentage = null, shareWeight = weight.trim())
                else
                    beneficiary
            },
        )

        calculateShares()
    }

    fun getExpenseGroupUuid() = groupUuid

    fun isOneTimeExpense(): Boolean = groupUuid == null
}

data class CreateExpenseState(
    val timestamp: String = "",
    val title: String = "",
    val description: String = "",

    /** The amount, as the form uses and saves it, see [toAmount]. */
    val amount: String = "",

    /** The amount field's text, as typed until the field is left, see [CreateExpenseViewModel.updateAmount]. */
    val amountText: String = "",
    val creditorUuid: String = "",
    val creditorFullName: String = "",
    val isSettlement: Boolean = false,
    val currency: Currency,
    val beneficiaries: List<Beneficiary> = emptyList(),

    /**
     * The text of each share field being typed in, by the beneficiary's user uuid, see
     * [CreateExpenseViewModel.typeBeneficiaryShare]: the share itself is in [beneficiaries].
     */
    val shareTexts: Map<String, String> = emptyMap(),

    /**
     * Whether the expense is deleted, as this device showed it once the form was read, or as it was
     * saved, see [ExpenseSaveError.DeletedMeanwhile]: the form then saves nothing, the detail screen
     * offering no edit of a deleted expense either.
     */
    val isDeleted: Boolean = false,

    /**
     * The revision of the version the form was read from, see [ExpenseWithBeneficiaries.revision]:
     * the one an edit is written after.
     */
    val revision: Long? = null,
    private val validateExpenseCallBack: (String, Currency, List<Beneficiary>) -> Int?,
) {
    /**
     * [title] without the whitespace around it, as the expense is saved with it, which the form is
     * checked and compared by, see [canConfirmText] and [isChangedFrom]. The field holds it as
     * typed: trimmed as it is typed, a space typed after a word would be swallowed, and the cursor
     * moved back over it.
     */
    val trimmedTitle: String = title.trim()

    /** [description] without the whitespace around it, as [trimmedTitle] is [title]. */
    val trimmedDescription: String = description.trim()

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
 * Whether the title and description of [this] let the expense be confirmed, taken as they are
 * saved, without the whitespace around them, see [CreateExpenseState.trimmedTitle]: a title of no
 * more than [maxTitleLength], and a description of no more than [maxDescriptionLength]. The screen
 * checks the rest besides: an amount the rules take, and for an expense being edited, a change,
 * see [isChangedFrom].
 */
fun CreateExpenseState.canConfirmText(maxTitleLength: Int, maxDescriptionLength: Int): Boolean =
    trimmedTitle.length in (1..maxTitleLength) &&
        trimmedDescription.length in (0..maxDescriptionLength)

/**
 * Whether [this], the form of an expense being edited, differs from [loaded], the form as the
 * expense was read into it, in what saving it writes, see [savedContent].
 */
fun CreateExpenseState.isChangedFrom(loaded: CreateExpenseState): Boolean =
    savedContent() != loaded.savedContent()

/**
 * What saving the form writes of the expense, and so what an edit has to change. The title and
 * description are taken as they are saved, without the whitespace around them, see
 * [CreateExpenseState.trimmedTitle]: whitespace typed around them changes nothing. Each share is
 * taken by whose it is: working the shares out again gives every beneficiary a new identifier,
 * which changes nothing either.
 */
private fun CreateExpenseState.savedContent(): List<Any> =
    listOf(
        trimmedTitle,
        trimmedDescription,
        amount,
        currency,
        creditorUuid,
        beneficiaries.mapTo(HashSet()) { listOf(it.userUuid, it.share, it.isShareFixed, it.sharePercentage, it.shareWeight) },
    )

/** The percentages the shares of [this] were set as, by the beneficiary's user uuid. */
private fun List<Beneficiary>.sharePercentages(): Map<String, String> =
    mapNotNull { beneficiary -> beneficiary.sharePercentage?.let { beneficiary.userUuid to it } }.toMap()

/**
 * The weights the shares of [this] were set as by hand, by the beneficiary's user uuid: not the `1`
 * the library states for who was given none, which would keep the expense using weights once
 * nobody set one any more.
 */
private fun List<Beneficiary>.shareWeightsSetByHand(): Map<String, String> =
    filter { it.isShareFixed }
        .mapNotNull { beneficiary -> beneficiary.shareWeight?.let { beneficiary.userUuid to it } }
        .toMap()

/** Whether this share was named as an amount by hand, and not set as a percentage or a weight. */
private fun Beneficiary.isFixedAmount(): Boolean =
    isShareFixed && sharePercentage == null && shareWeight == null

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
