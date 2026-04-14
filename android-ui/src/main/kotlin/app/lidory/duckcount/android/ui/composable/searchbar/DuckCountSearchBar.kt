package app.lidory.duckcount.android.ui.composable.searchbar

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.android.ui.R
import app.lidory.duckcount.android.ui.composable.textfield.DuckCountTextField

@Composable
fun DuckCountSearchBar(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    focusManager: FocusManager,
    modifier: Modifier = Modifier,
) {
    DuckCountTextField(
        modifier = modifier,
        value = searchQuery,
        onValueChange = onSearchChange,
        placeholder = { Text(text = stringResource(R.string.search)) },
        leadingIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = stringResource(R.string.search),
            )
        },
        singleLine = true,
        error = "",
        isInError = false,
        focusManager = focusManager,
    )
}