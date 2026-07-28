package app.lidory.duckcount.expenseList.component.beneficiaryList

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.expenseList.R
import app.lidory.duckcount.android.ui.R as androidUiR

@Composable
fun BeneficiaryListHeader(
    numberOfBeneficiaries: Int,
    enabled: Boolean,
    onModifyBeneficiaryList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = pluralStringResource(R.plurals.beneficiaries, numberOfBeneficiaries))

        TextButton(
            enabled = enabled,
            onClick = onModifyBeneficiaryList,
        ) {
            val iconSize = with(LocalDensity.current) {
                (LocalTextStyle.current.fontSize * 1.2f).toDp()
            }

            val iconRes = if (numberOfBeneficiaries == 0) androidUiR.drawable.ic_add else androidUiR.drawable.ic_edit
            val labelRes =
                if (numberOfBeneficiaries == 0) androidUiR.string.add else androidUiR.string.modify

            Icon(
                painter = painterResource(iconRes),
                contentDescription = stringResource(labelRes),
                modifier = Modifier.size(iconSize),
            )

            Spacer(Modifier.width(4.dp))

            Text(stringResource(labelRes))
        }
    }
}
