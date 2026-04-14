package component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.ExpenseError
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.expenselist.R
import database.beneficiary.Beneficiary

@Composable
fun BeneficiaryPart(
    beneficiaries: List<Beneficiary>,
    onBeneficiaryAmountChange: (String, String) -> Unit,
    onAddUser: () -> Unit,
    expenseError: ExpenseError?,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Padding.medium),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Padding.medium),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {

            Text(text = stringResource(R.string.concerned_users))

            IconButton(
                onClick = onAddUser,
            ) {
                Icon(painter = painterResource(R.drawable.ic_add), contentDescription = "Add")
            }
        }

        ExpenseErrorCard(expenseError)

        beneficiaries.forEach { beneficiary ->
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
                ExpenseTextField(
                    value = beneficiary.share,
                    onValueChange = {
                        onBeneficiaryAmountChange(
                            beneficiary.userUuid, it,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}