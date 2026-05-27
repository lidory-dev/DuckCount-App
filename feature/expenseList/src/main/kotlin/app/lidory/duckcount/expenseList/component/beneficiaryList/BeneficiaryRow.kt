package app.lidory.duckcount.expenseList.component.beneficiaryList

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.android.ui.composable.textfield.DuckCountTextField
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.expenseList.model.Beneficiary

@Composable
fun BeneficiaryRow(
    onBeneficiaryAmountChange: (String, String) -> Unit,
    beneficiary: Beneficiary,
    focusManager: FocusManager,
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
            text = beneficiary.userUuid,
            modifier = Modifier
                .weight(4f)
                .padding(Padding.medium),
        )

        DuckCountTextField(
            value = beneficiary.share,
            onValueChange = {
                onBeneficiaryAmountChange(
                    beneficiary.userUuid, it,
                )
            },
            focusManager = focusManager,
            modifier = Modifier.weight(1f),
        )
    }
}