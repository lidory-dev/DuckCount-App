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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.validateExpense
import component.AmountTextField
import component.BeneficiariesDialog
import component.BeneficiaryPart
import component.CreditorDialog
import component.CreditorSelector
import component.CustomTextField
import component.TopBar
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
        onAddUsers = {
            it.forEach { user ->
                viewModel.addBeneficiary(Beneficiary(uuid = "", expenseUuid = "", userUuid = user.uuid, share = "0"))
            }
        },
        focusManager = focusManager,
    )

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
            .padding(Padding.medium),
        verticalArrangement = Arrangement.spacedBy(Padding.medium),
    ) {
        item {
            TopBar(
                title = "Ajouter une nouvelle dépense",
                onBack = onBack,
            )
        }

        item {
            CustomTextField(
                value = expenseState.title,
                labelName = "Titre de la dépense",
                onValueChange = { viewModel.updateTitle(title = it) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                focusManager = focusManager,
                error = "Le champ ne peut pas être vide",
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
                label = "Payé par",
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
            CustomTextField(
                value = expenseState.description,
                labelName = "Description",
                onValueChange = { viewModel.updateDescription(description = it) },
                singleLine = false,
                maxLines = 5,
                focusManager = focusManager,
                error = "Le champ ne peut pas être vide",
                isInError = expenseState.description.isBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            BeneficiaryPart(
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
            )
        }

        val isExpenseValide = runCatching {
            validateExpense(expenseState.amount, expenseState.currency, expenseState.beneficiaries)
        }
        item {
            Button(
                enabled = isExpenseValide != null,
                onClick = {
                    viewModel.createExpense()
                    onBack()
                },
            ) {
                Text(text = "Valider")
            }
        }
    }
}

