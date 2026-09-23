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

@Composable
fun BeneficiaryRow(
    enabled: Boolean,
    onBeneficiaryAmountChange: (String, String) -> Unit,
    beneficiary: BeneficiaryUi,
    currencySymbol: String,
    removeFixedShare: () -> Unit,
    focusManager: FocusManager,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(75.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(.7f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                text = beneficiary.fullName ?: stringResource(R.string.unknown_person),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(Padding.medium),
            )

            if (beneficiary.isShareFixed) {
                IconButton(
                    onClick = removeFixedShare,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_undo),
                        contentDescription = stringResource(R.string.remove_fixed_share),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DuckCountTextField(
                value = beneficiary.share,
                enabled = enabled,
                onValueChange = {
                    onBeneficiaryAmountChange(
                        beneficiary.userUuid, it,
                    )
                },
                keyboardOptions = KeyboardOptions.Default.copy(
                    keyboardType = KeyboardType.Number,
                ),
                focusManager = focusManager,
                modifier = Modifier.fillMaxWidth(.75f),
            )

            Text(
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                text = currencySymbol,
                modifier = Modifier
                    .padding(Padding.medium),
            )
        }
    }
}
