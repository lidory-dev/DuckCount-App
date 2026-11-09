package app.lidory.duckcount.expenseList

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import app.lidory.duckcount.android.ui.composable.DuckCountSnackbarHost
import app.lidory.duckcount.android.ui.composable.button.ButtonBottomPadding
import app.lidory.duckcount.android.ui.composable.button.DuckCountElevatedLoadingButton
import app.lidory.duckcount.android.ui.composable.button.isButtonPinned
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
import app.lidory.duckcount.expenseList.viewmodel.canConfirmText
import org.koin.androidx.compose.koinViewModel
import app.lidory.duckcount.android.ui.R as androidUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateExpenseScreen(
    viewModel: CreateExpenseViewModel = koinViewModel(),
    onBack: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }

    val userState by viewModel.usersState.collectAsStateWithLifecycle()
    val selectedUuids by viewModel.selectedUuids.collectAsStateWithLifecycle()
    val selectionToggleState by viewModel.selectionToggleState.collectAsStateWithLifecycle()
    val beneficiaryNames by viewModel.beneficiaryNames.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val isSaved by viewModel.isSaved.collectAsStateWithLifecycle()
    val hasChanges by viewModel.hasChanges.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var pendingError by remember { mutableStateOf<ExpenseSaveError?>(null) }

    // Do not allow navigating back while saving the expense.
    BackHandler(enabled = isSaving) {}

    val isButtonPinned = isButtonPinned()

    val confirmButton: @Composable (Modifier) -> Unit = { modifier ->
        DuckCountElevatedLoadingButton(
            modifier = modifier.fillMaxWidth(),
            enabled = !isSaving
                    && !isSaved
                    && (viewModel.isNewExpense() || hasChanges)
                    && !viewModel.state.isDeleted
                    && viewModel.state.error == null
                    && viewModel.state.amount.isNotBlank()
                    && viewModel.state.canConfirmText(
                        maxTitleLength = viewModel.maxTitleLength,
                        maxDescriptionLength = viewModel.maxDescriptionLength,
                    ),
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
        onSelectedCreditor = { viewModel.updateCreditor(creditorUuid = it) },
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
        // Out of the column, which ends above the bar, and the bar above the keyboard, which
        // pushes it up: the button stays in reach as on every form, and the column scrolls the
        // field being filled into the room left above it. In the bar only while the window has
        // room for it there, see isButtonPinned, at the end of the column otherwise.
        bottomBar = {
            // The keyboard's inset holds the navigation bar's while it is open.
            Box(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
                if (isButtonPinned) {
                    confirmButton(
                        Modifier
                            .padding(horizontal = Padding.medium)
                            .padding(bottom = ButtonBottomPadding),
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                // Read as the column is laid out, so that the bar moving with the keyboard
                // recomposes nothing.
                .padding(innerPadding)
                .padding(horizontal = Padding.medium)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                ) {
                    focusManager.clearFocus()
                }
                .fillMaxSize(),
        ) {
            LazyColumn(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                // The last row as far from the button below as the rows are from each other, or,
                // while the button is that row, it ButtonBottomPadding off the keyboard, as
                // everywhere.
                contentPadding = PaddingValues(
                    bottom = if (isButtonPinned) Padding.medium else ButtonBottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(Padding.medium),
            ) {
                item {
                    DuckCountTextField(
                        value = viewModel.state.title,
                        labelName = stringResource(R.string.expense_title),
                        enabled = !isSaving,
                        onValueChange = { viewModel.updateTitle(title = it) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        focusManager = focusManager,
                        // Counted as it is saved, without the whitespace around it, as the button
                        // counts it, see canConfirmText.
                        error =
                            if (viewModel.state.trimmedTitle.isEmpty())
                                stringResource(androidUiR.string.title_mandatory)
                            else
                                stringResource(
                                    R.string.title_too_long,
                                    viewModel.state.trimmedTitle.length,
                                    viewModel.maxTitleLength,
                                ),
                        isInError = viewModel.state.trimmedTitle.length !in (1..viewModel.maxTitleLength),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                item {
                    CreditorSelector(
                        creditorName = viewModel.state.creditorFullName,
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
                        amountValue = viewModel.state.amountText,
                        acceptsAmount = viewModel::acceptsAmount,
                        isAmountMissing = viewModel.state.amount.isBlank(),
                        currencyValue = viewModel.state.currency.symbol,
                        currencies = viewModel.currencies,
                        currencyDisplayName = viewModel::currencyDisplayName,
                        enabled = !isSaving,
                        onAmountValueChange = { amount ->
                            viewModel.updateAmount(amount)
                            viewModel.calculateShares()
                        },
                        onAmountFinished = viewModel::finishAmount,
                        onCurrencyValueChange = { currency ->
                            viewModel.selectCurrency(currency)
                            focusManager.clearFocus()
                        },
                        focusManager = focusManager,
                        groupUuid = viewModel.getExpenseGroupUuid(),
                    )
                }

                item {
                    DuckCountTextField(
                        value = viewModel.state.description,
                        labelName = stringResource(R.string.label_description),
                        enabled = !isSaving,
                        onValueChange = { viewModel.updateDescription(description = it) },
                        singleLine = false,
                        maxLines = 5,
                        // Counted as the title is, without the whitespace around it.
                        error = stringResource(
                            R.string.description_too_long,
                            viewModel.state.trimmedDescription.length,
                            viewModel.maxDescriptionLength,
                        ),
                        isInError = viewModel.state.trimmedDescription.length > viewModel.maxDescriptionLength,
                        focusManager = focusManager,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                beneficiaryList(
                    beneficiaries = viewModel.displayedBeneficiaries(names = beneficiaryNames),
                    maxBeneficiaries = viewModel.maxBeneficiaries,
                    currencySymbol = viewModel.state.currency.symbol,
                    enabled = !isSaving,
                    onBeneficiaryAmountChange = { userUuid, share ->
                        viewModel.typeBeneficiaryShare(
                            userUuid = userUuid,
                            text = share,
                        )
                        viewModel.setBeneficiaryFixedShare(userUuid)
                        viewModel.calculateShares()
                    },
                    acceptsShare = viewModel::acceptsAmount,
                    onShareFinished = { userUuid -> viewModel.finishBeneficiaryShare(userUuid = userUuid) },
                    onModifyBeneficiaryList = {
                        viewModel.showBeneficiariesDialog()
                        focusManager.clearFocus()
                    },
                    expenseError = viewModel.state.error,
                    focusManager = focusManager,
                    removeBeneficiaryFixedShare = { userUuid ->
                        viewModel.removeBeneficiaryFixedShare(userUuid = userUuid)
                    },
                )

                // Out of the bar while the window is too short for it there, see isButtonPinned.
                if (!isButtonPinned) {
                    item { confirmButton(Modifier) }
                }
            }

            DuckCountSnackbarHost(hostState = snackbarHostState)
        }
    }
}
