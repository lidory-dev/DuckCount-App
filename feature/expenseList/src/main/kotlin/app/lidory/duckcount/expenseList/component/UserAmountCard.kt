package app.lidory.duckcount.expenseList.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.lidory.duckcount.android.ui.composable.profilepicture.ProfilePictureCard
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.expenseList.model.Currency
import app.lidory.duckcount.expenseList.R
import androidx.compose.ui.res.stringResource

@Composable
fun UserAmountCard(
    modifier: Modifier = Modifier,
    userUuid: String,

    /** Null for somebody this device holds no profile for. */
    userFullName: String?,
    amount: String,
    currency: Currency,
) {
    Card(
        shape = RoundedCornerShape(size = Padding.large),
        modifier = modifier
            .fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = Padding.medium)
                .height(TextFieldDefaults.MinHeight),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val displayName = userFullName ?: stringResource(R.string.unknown_person)

                ProfilePictureCard(
                    uuid = userUuid,
                    fullName = displayName,
                )

                Text(
                    text = displayName,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // TODO: The currency symbol might be before the amount.
            Text(
                modifier = Modifier.padding(horizontal = Padding.medium),
                text = "$amount ${currency.symbol}",
            )
        }
    }
}
