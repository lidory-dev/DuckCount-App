package app.lidory.duckcount.expenseList.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.expenseList.callBacks.CreateExpenseCallBacks
import app.lidory.duckcount.expenseList.model.Beneficiary
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.expenseList.model.User
import app.lidory.duckcount.expenseList.utils.formatAmountWithSubunits
import app.lidory.duckcount.expenseList.utils.getCurrentTimestamp
import app.lidory.duckcount.expenseList.utils.roundAmountWithSubunits
import app.lidory.duckcount.expenseList.utils.roundAmountWithoutSubunits
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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
    private val createExpenseCallBacks: CreateExpenseCallBacks,
) : ViewModel() {

    val maxDescriptionLength: Int = createExpenseCallBacks.maxDescriptionLength

    var isCreditorDialogShown by mutableStateOf(false)

    var isBeneficiariesDialogShown by mutableStateOf(false)

    // TODO: use real data
    @OptIn(ExperimentalUuidApi::class)
    val creditorUuid: String = Uuid.random().toString()

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

        val droppedFraction = hasSubunits && previous.contains('.') && !rounded.contains('.')
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
            creditorUuid = creditorUuid,
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
        getCreditorName()
        updateCurrency(currencies.first().name, currencies.first().symbol, currencies.first().hasSubunits)
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
            ),
        )
    }

    fun getCreditorName() {
//         TODO: Use creditorUuid, supposed to be the current connected user
//         For now we don't have correct creditorUuid so we use a placeholder
//        userRepository.getFullName(creditorUuid).onEach {
//            updateCreditorFullName(it)
//        }
//            .launchIn(viewModelScope)
        updateCreditor("Test User")
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
    }

    fun updateCreditorFullName(creditorFullName: String) {
        _state.update {
            it.copy(creditorFullName = creditorFullName)
        }
    }

    fun updateBeneficiaries(beneficiaries: List<Beneficiary>) {
        _state.update { state ->
            val existingUuids = state.beneficiaries.map { it.uuid }.toSet()
            state.copy(
                beneficiaries = state.beneficiaries.filter { it in beneficiaries } +
                        beneficiaries.filterNot { it.uuid in existingUuids },
            )
        }
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

}

data class CreateExpenseState(
    val timestamp: String = "",
    val title: String = "",
    val description: String = "",
    val amount: String = "",
    val creditorUuid: String = "",
    // TODO: use the current connected user full name
    val creditorFullName: String = "",
    val isRefund: Boolean = false,
    // TODO: adapt
    val currency: Currency = Currency("", "", "", false),
    val beneficiaries: List<Beneficiary> = emptyList(),
    private val validateExpenseCallBack: (String, Currency, List<Beneficiary>) -> Int?,
) {
    val error: Int? = validateExpenseCallBack(amount, currency, beneficiaries)
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
