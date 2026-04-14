package component.BeneficiaryList

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.expenselist.R

@Composable
fun BeneficiaryListHeader(
    onAddUser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
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
}