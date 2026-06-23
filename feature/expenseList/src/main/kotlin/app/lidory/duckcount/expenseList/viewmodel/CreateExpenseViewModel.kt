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
import app.lidory.duckcount.expenseList.model.User
import app.lidory.duckcount.expenseList.utils.formatAmountWithSubunits
import app.lidory.duckcount.expenseList.utils.getCurrentTimestamp
import app.lidory.duckcount.expenseList.utils.roundAmountWithSubunits
import app.lidory.duckcount.expenseList.utils.roundAmountWithoutSubunits
import app.lidory.duckcount.expenseList.utils.toValidNumber
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
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

    var isCreditorDialogShown by mutableStateOf(false)

    var isBeneficiariesDialogShown by mutableStateOf(false)

    // TODO: remove when currencyRepository is implemented
    @OptIn(ExperimentalUuidApi::class)
    val currencies =
        listOf(
            Currency(uuid = Uuid.random().toString(), "EUR", "€", true),
            Currency(uuid = Uuid.random().toString(), "USD", "$", true),
            Currency(uuid = Uuid.random().toString(), "JPY", "¥", false),
        )

    private fun normalizeAmountInput(raw: String, previous: String): String? {
        val formatted = raw.formatAmountWithSubunits()
        if (!formatted.matches(Regex("""^\d*\.?\d*$"""))) return null

        val hasSubunits = _state.value.currency.hasSubunits
        val rounded = when {
            formatted.isBlank() -> formatted
            hasSubunits -> formatted.roundAmountWithSubunits()
            else -> formatted.roundAmountWithoutSubunits()
        }

        val droppedFraction =
            hasSubunits
                    && previous.contains('.')
                    && !rounded.contains('.')
                    && rounded.takeLast(2) == previous.takeLast(2)
        return if (droppedFraction) rounded.dropLast(2) else rounded
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
            validateExpenseCallBack = { amount, currency, beneficiaries ->
                createExpenseCallBacks.validateExpense(
                    amount = amount,
                    currency = currency,
                    beneficiaries = beneficiaries,
                )
            },
        ),
    )
    val state = _state.onStart {
        updateCreditor(creditorUuid = createExpenseCallBacks.getAuthenticatedUserUuid() ?: "")

        getCreditorName()

        updateCurrency(currencies.first().name, currencies.first().symbol, currencies.first().hasSubunits)

        expenseUuid?.let { uuid ->
            val expense = createExpenseCallBacks.getExpenseWithBeneficiaries(expenseUuid = uuid).first()

            updateTitle(expense.title)
            updateCreditor(expense.creditorUuid)
            updateAmount(expense.amount)
            updateCurrency(expense.currency.name, expense.currency.symbol, expense.currency.hasSubunits)
            updateDescription(expense.description)
            updateBeneficiaries(expense.beneficiaries)
            expense.beneficiaries.forEach {
                updateBeneficiaryShare(it.userUuid, it.share)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = _state.value,
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
                    fixedShare = beneficiary.fixedShare,
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList(),
        )

    fun toggleSelectedUser(user: User) {
        _selectedUuids.update { selected ->
            if (user.uuid in selected) selected - user.uuid else selected + user.uuid
        }
    }

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
        val expenseUuid = Uuid.random().toString()
        createExpenseCallBacks.insertWithBeneficiaries(
            expenseWithBeneficiaries = ExpenseWithBeneficiaries(
                // TODO: get uuid from server
                uuid = expenseUuid,
                timestamp = getCurrentTimestamp(),
                title = _state.value.title,
                description = _state.value.description,
                amount = _state.value.amount,
                creditorUuid = _state.value.creditorUuid,
                isRefund = _state.value.isRefund,
                currency = _state.value.currency,
                groupUuid = groupUuid,
                beneficiaries = _state.value.beneficiaries.map {
                    it.copy(expenseUuid = expenseUuid)
                },
                isDeleted = _state.value.isDeleted,
            ),
        )
    }

    fun updateExpense() = viewModelScope.launch {
        expenseUuid?.let {
            createExpenseCallBacks.updateExpenseWithBeneficiaries(
                expenseWithBeneficiaries = ExpenseWithBeneficiaries(
                    uuid = expenseUuid,
                    timestamp = getCurrentTimestamp(),
                    title = _state.value.title,
                    description = _state.value.description,
                    amount = _state.value.amount,
                    creditorUuid = _state.value.creditorUuid,
                    isRefund = _state.value.isRefund,
                    currency = _state.value.currency,
                    groupUuid = groupUuid,
                    beneficiaries = _state.value.beneficiaries.map {
                        it.copy(expenseUuid = expenseUuid)
                    },
                    isDeleted = _state.value.isDeleted,
                ),
            )
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

    fun updateCurrency(name: String, symbol: String, hasSubUnit: Boolean) {
        // TODO: UUID.
        val newCurrency = Currency(uuid = "", name = name, symbol = symbol, hasSubunits = hasSubUnit)

        _state.update { state ->
            val reformat: (String) -> String = { value ->
                when {
                    value.isBlank() -> value
                    hasSubUnit -> value.roundAmountWithSubunits()
                    else -> value.roundAmountWithoutSubunits()
                }
            }

            state.copy(
                currency = newCurrency,
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

    fun onBeneficiariesSelected(uuids: Set<String>) {
        val fixedBeneficiaries = _state.value.beneficiaries.filter { it.fixedShare }
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
                if (beneficiary.userUuid in fixedShareUuids) beneficiary.copy(fixedShare = true) else beneficiary
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
                beneficiary.copy(fixedShare = true)
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
                .filter { it.fixedShare }
                .associate { it.userUuid to (it.share.toValidNumber()) },
        )

        beneficiaries.forEach {
            updateBeneficiaryShare(it.userUuid, it.share)
        }
    }

    fun fixBeneficiary(userUuid: String) {
        _state.update {
            it.copy(
                beneficiaries = it.beneficiaries.map { beneficiary ->
                    if (beneficiary.userUuid == userUuid)
                        beneficiary.copy(fixedShare = true)
                    else
                        beneficiary
                },
            )
        }
    }
}

data class CreateExpenseState(
    val timestamp: String = "",
    val title: String = "",
    val description: String = "",
    val amount: String = "",
    val creditorUuid: String = "",
    val creditorFullName: String = "",
    val isRefund: Boolean = false,
    // TODO: adapt
    val currency: Currency = Currency("", "", "", false),
    val beneficiaries: List<Beneficiary> = emptyList(),
    val isDeleted: Boolean = false,
    private val validateExpenseCallBack: (String, Currency, List<Beneficiary>) -> Int?,
) {
    val error: Int? =
        if (beneficiaries.any { it.share.isBlank() }) R.string.non_positive_amount_share
        else validateExpenseCallBack(amount, currency, beneficiaries)
}

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
