package viewmodel

import androidx.lifecycle.ViewModel
import database.currency.CurrencyRepository

class CurrencyViewModel(
    private val currencyRepository: CurrencyRepository,
) : ViewModel() {
//    private var observeCurrenciesJob: Job? = null
//
//    private val _state = MutableStateFlow(CurrencyState())
//    val state = _state.onStart {
//        observeCurrencies()
//    }.stateIn(
//        scope = viewModelScope,
//        started = SharingStarted.WhileSubscribed(5000L),
//        initialValue = _state.value,
//    )

//    private fun observeCurrencies() {
//        observeCurrenciesJob?.cancel()
//        observeCurrenciesJob = currencyRepository
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
}

data class CurrencyState(
    val name: String = "",
    val symbol: String = "",
    val hasSubUnits: Boolean = true,
)