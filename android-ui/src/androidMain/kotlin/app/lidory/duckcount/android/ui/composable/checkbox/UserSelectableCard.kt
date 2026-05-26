package app.lidory.duckcount.android.ui.composable.checkbox

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
import app.lidory.duckcount.android.ui.constant.Padding

@Composable
fun UserSelectableCard(
    modifier: Modifier = Modifier,
    toggleSelectedUser: () -> Unit,
    fullName: String,
    isChecked: Boolean,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(size = Padding.large),
        onClick = toggleSelectedUser,
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(TextFieldDefaults.MinHeight),
        ) {
            Text(
                text = fullName,
                modifier = Modifier
                    .padding(TextFieldDefaults.contentPaddingWithLabel()),
            )

            DuckCountCheckbox(
                checked = isChecked,
                onCheckedChange = {
                    toggleSelectedUser()
                },
            )
        }
    }
}