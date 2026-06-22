package app.lidory.duckcount.expenseList.component.beneficiaryList

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.android.ui.composable.textfield.DuckCountTextField
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.expenseList.R
import app.lidory.duckcount.expenseList.model.BeneficiaryUi
import app.lidory.duckcount.expenseList.utils.CurrencySymbolTransformation

@Composable
fun BeneficiaryRow(
    onBeneficiaryAmountChange: (String, String) -> Unit,
    beneficiary: BeneficiaryUi,
    currencySymbol: String,
    focusManager: FocusManager,
    unfixAmount: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(75.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            text = beneficiary.fullName,
            modifier = Modifier
                .weight(2f)
                .padding(Padding.medium),
        )

        DuckCountTextField(
            value = beneficiary.share,
            onValueChange = {
                onBeneficiaryAmountChange(
                    beneficiary.userUuid, it,
                )
            },
            keyboardOptions = KeyboardOptions.Default.copy(
                keyboardType = KeyboardType.Number,
            ),
            focusManager = focusManager,
            modifier = Modifier.weight(1f),
            trailingIcon = {
                if (beneficiary.fixedShare)
                    IconButton(
                        onClick = unfixAmount,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = stringResource(R.string.unfix),
                        )
                    }
            },
            visualTransformation = CurrencySymbolTransformation(currencySymbol),
        )
    }
}
