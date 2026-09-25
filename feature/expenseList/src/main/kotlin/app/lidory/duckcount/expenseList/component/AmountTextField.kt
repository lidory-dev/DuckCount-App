package app.lidory.duckcount.expenseList.component

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import app.lidory.duckcount.android.ui.composable.textfield.DuckCountTextField
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.expenseList.R
import app.lidory.duckcount.expenseList.model.Currency

@Composable
fun AmountTextField(
    amountValue: String,
    currencyValue: String,
    currencies: List<Currency>,
    currencyDisplayName: (String) -> String,
    enabled: Boolean,
    onAmountValueChange: (String) -> Unit,
    onCurrencyValueChange: (Currency) -> Unit,
    modifier: Modifier = Modifier,
    focusManager: FocusManager,
    groupUuid: String?,
) {
    var textFieldHeightPx by remember { mutableIntStateOf(0) }
    val localDensity = LocalDensity.current
    val textFieldHeightDp by remember {
        derivedStateOf {
            with(localDensity) { textFieldHeightPx.toDp() }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DuckCountTextField(
            value = amountValue,
            labelName = stringResource(R.string.amount_expense),
            modifier = Modifier
                .onSizeChanged { size ->
                    textFieldHeightPx = size.height
                },
            innerModifier = Modifier
                .fillMaxWidth(.75f),
            enabled = enabled,
            onValueChange = onAmountValueChange,
            keyboardOptions = KeyboardOptions.Default.copy(
                keyboardType = KeyboardType.Number,
            ),
            focusManager = focusManager,
            error = stringResource(R.string.amount_mandatory),
            isInError = amountValue.isBlank(),
        )

        Spacer(modifier = Modifier.padding(Padding.small))

        groupUuid?.let {
            DuckCountTextField(
                value = currencyValue,
                onValueChange = { },
                readOnly = true,
                enabled = true,
                modifier = Modifier
                    .fillMaxSize()
                    .height(textFieldHeightDp)
                    .animateContentSize(),
                focusManager = focusManager,
                labelName = null,
            )
        } ?: run {
            CurrencyDropDownMenu(
                currency = currencyValue,
                currencies = currencies,
                currencyDisplayName = currencyDisplayName,
                enabled = enabled,
                onValueChange = onCurrencyValueChange,
                focusManager = focusManager,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(textFieldHeightDp)
                    .animateContentSize(),
            )
        }
    }
}
