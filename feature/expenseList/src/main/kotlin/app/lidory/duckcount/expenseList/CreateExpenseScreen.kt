package app.lidory.duckcount.expenseList

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lidory.duckcount.android.ui.composable.button.DuckCountElevatedLoadingButton
import app.lidory.duckcount.android.ui.composable.textfield.DuckCountTextField
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.expenseList.component.AmountTextField
import app.lidory.duckcount.expenseList.component.BeneficiariesDialog
import app.lidory.duckcount.expenseList.component.CreditorDialog
import app.lidory.duckcount.expenseList.component.CreditorSelector
import app.lidory.duckcount.expenseList.component.beneficiaryList.beneficiaryList
import app.lidory.duckcount.expenseList.model.ExpenseSaveError
import app.lidory.duckcount.expenseList.model.message
import app.lidory.duckcount.expenseList.viewmodel.CreateExpenseViewModel
import org.koin.androidx.compose.koinViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateExpenseScreen(
    viewModel: CreateExpenseViewModel = koinViewModel(),
    onBack: () -> Unit,
) {
    val expenseState by viewModel.state.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }

    val userState by viewModel.usersState.collectAsStateWithLifecycle()
    val selectedUuids by viewModel.selectedUuids.collectAsStateWithLifecycle()
    val selectionToggleState by viewModel.selectionToggleState.collectAsStateWithLifecycle()
    val beneficiaries by viewModel.displayedBeneficiaries.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val isSaved by viewModel.isSaved.collectAsStateWithLifecycle()
    val hasChanges by viewModel.hasChanges.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var pendingError by remember { mutableStateOf<ExpenseSaveError?>(null) }

    // Do not allow navigating back while saving the expense.
    BackHandler(enabled = isSaving) {}

    // Navigate back only once write has completed (see CreateExpenseViewModel.isSaved), and
    // on resuming: onBack does nothing until the screen is resumed, so a write that completes
    // while the app is in the background closes the screen once the user is back.
    LifecycleResumeEffect(isSaved) {
        if (isSaved) onBack()

        onPauseOrDispose {}
    }

    // Surface write failures with a message that matches the actual cause.
    LaunchedEffect(Unit) {
        viewModel.showError.collect { pendingError = it }
    }

    pendingError?.let { error ->
        val message = error.message()
        LaunchedEffect(error) {
            snackbarHostState.showSnackbar(message)
            pendingError = null
        }
    }

    CreditorDialog(
        isDialogShown = viewModel.isCreditorDialogShown,
        onDismissRequest = { viewModel.hideCreditorDialog() },
        onSelectedCreditor = {
            viewModel.updateCreditor(creditorUuid = it)
            viewModel.getCreditorName()
        },
        focusManager = focusManager,
        usersList = userState.users,
        searchQuery = viewModel.searchQuery,
        clearSearchQuery = {
            viewModel.clearSearchQuery()
        },
        onSearchQueryChange = {
            viewModel.onSearchQueryChange(it)
        },
    )

    BeneficiariesDialog(
        isDialogShown = viewModel.isBeneficiariesDialogShown,
        onDismissRequest = { viewModel.hideBeneficiariesDialog() },
        onSelectUsers = { uuids -> viewModel.onBeneficiariesSelected(uuids) },
        focusManager = focusManager,
        usersList = userState.users,
        searchQuery = viewModel.searchQuery,
        clearSearchQuery = { viewModel.clearSearchQuery() },
        onSearchQueryChange = { viewModel.onSearchQueryChange(it) },
        toggleSelectAllUsers = { viewModel.toggleSelectAllUsers() },
        toggleSelectedUser = { viewModel.toggleSelectedUser(it) },
        toggleableListState = selectionToggleState,
        isSelectAllShown = !viewModel.isOneTimeExpense(),
        selectedUuids = selectedUuids,
        maxBeneficiaries = viewModel.maxBeneficiaries,
    )

    Scaffold(
        modifier = Modifier
            .fillMaxWidth(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (viewModel.isNewExpense()) stringResource(R.string.add_new_expense) else
                            stringResource(R.string.edit_expense),
                    )
                },
                navigationIcon = {
                    IconButton(
                        enabled = !isSaving,
                        onClick = onBack,
                    ) {
                        Icon(
                            painter = painterResource(androidUiR.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = Padding.medium,
                    start = Padding.medium,
                    end = Padding.medium,
                )
                .fillMaxSize(),
        ) {
            LazyColumn(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                    ) {
                        focusManager.clearFocus()
                    }
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(Padding.medium),
            ) {
                item {
                    DuckCountTextField(
                        value = expenseState.title,
                        labelName = stringResource(R.string.expense_title),
                        enabled = !isSaving,
                        onValueChange = { viewModel.updateTitle(title = it) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        focusManager = focusManager,
                        error =
                            if (expenseState.title.isBlank())
                                stringResource(androidUiR.string.title_mandatory)
                            else
                                stringResource(
                                    R.string.title_too_long,
                                    expenseState.title.length,
                                    viewModel.maxTitleLength,
                                ),
                        isInError = expenseState.title.length !in (1..viewModel.maxTitleLength),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                item {
                    CreditorSelector(
                        creditorName = expenseState.creditorFullName,
                        label = stringResource(R.string.paid_by),
                        enabled = !isSaving && !viewModel.isOneTimeExpense(),
                        onClick = {
                            viewModel.showCreditorDialog()
                            focusManager.clearFocus()
                        },
                    )
                }

                item {
                    // TODO: readOnly = isSaving,
                    AmountTextField(
                        amountValue = expenseState.amount,
                        currencyValue = expenseState.currency.symbol,
                        currencies = viewModel.currencies,
                        currencyDisplayName = viewModel::currencyDisplayName,
                        enabled = !isSaving,
                        onAmountValueChange = { amount ->
                            viewModel.updateAmount(amount)
                            viewModel.calculateShares()
                        },
                        onCurrencyValueChange = { currency ->
                            viewModel.updateCurrency(currency)
                            focusManager.clearFocus()
                        },
                        focusManager = focusManager,
                        groupUuid = viewModel.getExpenseGroupUuid(),
                    )
                }

                item {
                    DuckCountTextField(
                        value = expenseState.description,
                        labelName = stringResource(R.string.label_description),
                        enabled = !isSaving,
                        onValueChange = { viewModel.updateDescription(description = it) },
                        singleLine = false,
                        maxLines = 5,
                        error = stringResource(
                            R.string.description_too_long, expenseState.description.length,
                            viewModel.maxDescriptionLength,
                        ),
                        isInError = expenseState.description.length > viewModel.maxDescriptionLength,
                        focusManager = focusManager,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                beneficiaryList(
                    beneficiaries = beneficiaries,
                    maxBeneficiaries = viewModel.maxBeneficiaries,
                    currencySymbol = expenseState.currency.symbol,
                    enabled = !isSaving,
                    onBeneficiaryAmountChange = { userUuid, share ->
                        viewModel.updateBeneficiaryShare(
                            userUuid = userUuid,
                            share = share,
                        )
                        viewModel.setBeneficiaryFixedShare(userUuid)
                        viewModel.calculateShares()
                    },
                    onModifyBeneficiaryList = {
                        viewModel.showBeneficiariesDialog()
                        focusManager.clearFocus()
                    },
                    expenseError = expenseState.error,
                    focusManager = focusManager,
                    removeBeneficiaryFixedShare = { userUuid ->
                        viewModel.removeBeneficiaryFixedShare(userUuid = userUuid)
                    },
                )

                item {
                    // Spacer to place the last element above the add button
                    Spacer(modifier = Modifier.padding(ButtonDefaults.MinHeight))
                }
            }

            DuckCountElevatedLoadingButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Padding.extraLarge)
                    .align(Alignment.BottomCenter),
                enabled = !isSaving
                        && !isSaved
                        && (viewModel.isNewExpense() || hasChanges)
                        && expenseState.error == null
                        && expenseState.amount.isNotBlank()
                        && expenseState.title.length in (1..viewModel.maxTitleLength)
                        && expenseState.description.length in (0..viewModel.maxDescriptionLength),
                colors = ButtonDefaults.buttonColors()
                    .copy(containerColor = MaterialTheme.colorScheme.primary),
                onClick = {
                    if (viewModel.isNewExpense())
                        viewModel.createExpense()
                    else
                        viewModel.updateExpense()
                    // Navigation happens via viewModel.isSaved once write completes,
                    // otherwise popping this screen cancels the in-flight network write.
                },
                isLoading = isSaving,
            ) {
                Text(
                    modifier = Modifier.padding(Padding.small),
                    text = stringResource(androidUiR.string.confirm),
                )
            }
        }
    }
}
