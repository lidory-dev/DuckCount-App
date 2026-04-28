package viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lidory.duckcount.ExpenseError
import app.lidory.duckcount.validateExpense
import database.beneficiary.Beneficiary
import database.beneficiary.BeneficiaryRepository
import database.currency.Currency
import database.expense.Expense
import database.expense.ExpenseRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import utils.formatAmountWithSubunits
import utils.getCurrentTimestamp
import utils.roundAmountWithSubunits
import utils.roundAmountWithoutSubunits
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class CreateExpenseViewModel(
    private val expenseRepository: ExpenseRepository,
    // TODO: add when necessary
    // private val userRepository: UserRepository,
    // private val currencyRepository: CurrencyRepository,
    private val beneficiaryRepository: BeneficiaryRepository,
) : ViewModel() {

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

    @OptIn(ExperimentalUuidApi::class)
    fun createExpense() = viewModelScope.launch {
        expenseRepository.insert(

            expense = Expense(
                // TODO: get uuid from server
                uuid = Uuid.random().toString(),
                timestamp = getCurrentTimestamp(),
                title = _state.value.title,
                description = _state.value.description,
                amount = _state.value.amount,
                creditorUuid = _state.value.creditorUuid,
                isRefund = _state.value.isRefund,
                currency = _state.value.currency,
            ),
        )
        _state.value.beneficiaries.map { beneficiaryRepository.insert(beneficiary = it) }
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

    fun addBeneficiary(beneficiary: Beneficiary) {
        _state.update {
            it.copy(beneficiaries = _state.value.beneficiaries + beneficiary)
        }
    }

    fun addBeneficiaries(beneficiaries: List<Beneficiary>) {
        _state.update {
            it.copy(beneficiaries = _state.value.beneficiaries + beneficiaries)
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

    companion object {
        const val MAX_DESCRIPTION_LINE = 2000
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
) {
    val error: ExpenseError? = validateExpense(amount, currency, beneficiaries)
}
