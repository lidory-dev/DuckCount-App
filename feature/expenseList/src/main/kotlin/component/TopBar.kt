package component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import app.lidory.expenselist.R

@Composable
fun TopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
        ) {
            IconButton(
                onClick = onBack,
            ) {
                Icon(painter = painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
            }
            Text(
                text = title,
                modifier = Modifier,
                fontSize = 30.sp,
                lineHeight = 45.sp,
            )
        }
    }
}