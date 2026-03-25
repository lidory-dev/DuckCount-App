package viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import database.beneficiary.Beneficiary
import database.beneficiary.BeneficiaryRepository
import database.currency.Currency
import database.expense.Expense
import database.expense.ExpenseRepository
import database.user.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class CreateExpenseViewModel(
    private val creditorUuid: String,
    private val expenseRepository: ExpenseRepository,
    private val userRepository: UserRepository,
    private val beneficiaryRepository: BeneficiaryRepository,
) : ViewModel() {
    // private var observeUsersJob: Job? = null

    private val _state = MutableStateFlow(
        CreateExpenseState(
            creditorUuid = creditorUuid,
        ),
    )
    val state = _state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = _state.value,
    )

//    private fun observeUsers() {
//        observeUsersJob?.cancel()
//        observeUsersJob = userRepository
//            .getAll()
//            .onEach { users ->
//                _state.update {
//                    it.copy(
//                        users = users,
//                    )
//                }
//            }
//            .launchIn(viewModelScope)
//    }

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

    @OptIn(ExperimentalTime::class)
    fun getCurrentTimestamp(): String {

        val timestamp = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).format(
            LocalDateTime.Formats.ISO,
        )

        return timestamp
    }

    fun updateTitle(title: String) {
        _state.update {
            it.copy(title = title)
        }
    }

    fun updateAmount(amount: String) {
        if (_state.value.currency.hasSubunits) {
            if (amount.isBlank() || amount.matches(Regex("^\\d*\\.?\\d*\$"))) {
                _state.update { it.copy(amount = amount) }
            }
        } else {
            if (amount.isBlank() || amount.matches(Regex("^\\d*\$"))) {
                _state.update { it.copy(amount = amount) }
            }
        }
    }

    fun updateCurrency(name: String, symbol: String, hasSubUnit: Boolean) {
        if (!hasSubUnit) {
            _state.update {
                it.copy(
                    amount = _state.value.amount.substringBefore("."),
                    // TODO: uuid
                    currency = Currency(uuid = "", name = name, symbol = symbol, hasSubunits = hasSubUnit),
                )
            }
        } else {
            _state.update {
                it.copy(
                    // TODO: uuid
                    currency = Currency(uuid = "", name = name, symbol = symbol, hasSubunits = hasSubUnit),
                )
            }
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
        _state.update {
            it.copy(
                beneficiaries = it.beneficiaries.map { beneficiary ->
                    if (beneficiary.userUuid == userUuid) {
                        beneficiary.copy(share = share)
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
    val isRefund: Boolean = false,
    // TODO: adapt
    val currency: Currency = Currency("", "", "", false),
    val beneficiaries: List<Beneficiary> = emptyList(),
)