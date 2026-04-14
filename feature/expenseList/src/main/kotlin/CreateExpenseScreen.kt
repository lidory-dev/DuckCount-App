import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.composable.textfield.DuckCountTextField
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.expenselist.R
import component.AmountTextField
import component.BeneficiariesDialog
import component.BeneficiaryList
import component.CreditorDialog
import component.CreditorSelector
import database.beneficiary.Beneficiary
import org.koin.androidx.compose.koinViewModel
import viewmodel.CreateExpenseViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateExpenseScreen(
    viewModel: CreateExpenseViewModel = koinViewModel(),
    onBack: () -> Unit,
) {
    val expenseState by viewModel.state.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }

    var isCreditorDialogShown by rememberSaveable { mutableStateOf(false) }

    var isConcernedUsersDialogShown by rememberSaveable { mutableStateOf(false) }

    CreditorDialog(
        isDialogShown = isCreditorDialogShown,
        onDismissRequest = { isCreditorDialogShown = !isCreditorDialogShown },
        onSelectedCreditor = { viewModel.updateCreditor(creditorUuid = it) },
        focusManager = focusManager,
    )

    BeneficiariesDialog(
        isDialogShown = isConcernedUsersDialogShown,
        onDismissRequest = { isConcernedUsersDialogShown = !isConcernedUsersDialogShown },
        onAddUsers = { users ->
            viewModel.addBeneficiaries(
                users.map { user -> Beneficiary(uuid = "", expenseUuid = "", userUuid = user.uuid, share = "0") },
            )
        },
        focusManager = focusManager,
    )

    Scaffold(
        modifier = Modifier
            .padding(horizontal = Padding.medium)
            .fillMaxWidth(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.add_new_expense),
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                ) {
                    focusManager.clearFocus()
                }
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
            verticalArrangement = Arrangement.spacedBy(Padding.medium),
        ) {
            item {
                DuckCountTextField(
                    value = expenseState.title,
                    labelName = stringResource(R.string.expense_title),
                    onValueChange = { viewModel.updateTitle(title = it) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    focusManager = focusManager,
                    error = stringResource(R.string.title_mandatory),
                    isInError = expenseState.title.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                CreditorSelector(
                    onClick = {
                        isCreditorDialogShown = !isCreditorDialogShown
                        focusManager.clearFocus()
                    },
                    creditorName = expenseState.creditorUuid,
                    label = stringResource(R.string.label_paid_by),
                )
            }

            item {
                AmountTextField(
                    amountValue = expenseState.amount,
                    currencyValue = expenseState.currency.symbol,
                    onExpenseValueChange = { amount ->
                        viewModel.updateAmount(amount)
                    },
                    onCurrencyValueChange = { name, symbol, hasSubUnit ->
                        viewModel.updateCurrency(name, symbol, hasSubUnit)
                        focusManager.clearFocus()
                    },
                    focusManager = focusManager,
                )
            }

            item {
                DuckCountTextField(
                    value = expenseState.description,
                    labelName = stringResource(R.string.label_description),
                    onValueChange = { viewModel.updateDescription(description = it) },
                    singleLine = false,
                    maxLines = 5,
                    focusManager = focusManager,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                BeneficiaryList(
                    beneficiaries = expenseState.beneficiaries,
                    onBeneficiaryAmountChange = { userId, share ->
                        viewModel.updateBeneficiaryShare(
                            userUuid = userId, share = share,
                        )
                    },
                    onAddUser = {
                        focusManager.clearFocus()
                        isConcernedUsersDialogShown = !isConcernedUsersDialogShown
                    },
                    expenseError = expenseState.error,
                )
            }

            item {
                Button(
                    enabled = expenseState.error == null,
                    onClick = {
                        viewModel.createExpense()
                        onBack()
                    },
                ) {
                    Text(text = stringResource(R.string.confirm))
                }
            }
        }
    }
}

