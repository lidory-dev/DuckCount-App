package app.lidory.duckcount.expenseList.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.expenseList.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditorSelector(
    creditorName: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // That of the form's text fields, whether or not the creditor can be changed: a card has a
    // colour of its own once disabled, which it always is for a one-time expense.
    val containerColor = TextFieldDefaults.colors().unfocusedContainerColor

    Card(
        shape = RoundedCornerShape(size = Padding.large),
        colors = CardDefaults.cardColors(containerColor = containerColor, disabledContainerColor = containerColor),
        enabled = enabled,
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth(),
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(TextFieldDefaults.contentPaddingWithLabel()),
            ) {
                Text(
                    text = label,
                    color = TextFieldDefaults.colors().unfocusedLabelColor,
                    style = MaterialTheme.typography.bodySmall,
                )

                Text(
                    text = creditorName,
                )
            }

            Icon(
                modifier = Modifier.padding(Padding.extraLarge),
                painter = painterResource(R.drawable.ic_person_edit),
                contentDescription = stringResource(R.string.edit),
            )
        }
    }
}
