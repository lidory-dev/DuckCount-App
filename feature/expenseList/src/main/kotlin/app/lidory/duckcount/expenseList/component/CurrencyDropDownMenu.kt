package app.lidory.duckcount.expenseList.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import app.lidory.duckcount.android.ui.composable.textfield.DuckCountTextField
import app.lidory.duckcount.expenseList.model.Currency

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrencyDropDownMenu(
    currency: String,
    currencies: List<Currency>,
    currencyDisplayName: (String) -> String,
    enabled: Boolean,
    onValueChange: (Currency) -> Unit,
    modifier: Modifier = Modifier,
    focusManager: FocusManager,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = modifier,
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = {
                if (enabled) expanded = !expanded
            },
        ) {
            DuckCountTextField(
                value = currency,
                onValueChange = { },
                enabled = enabled,
                readOnly = true,
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                colors = ExposedDropdownMenuDefaults.textFieldColors(
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusEvent {
                        if (it.isFocused && !expanded) {
                            focusManager.clearFocus()
                        }
                    }
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                focusManager = focusManager,
                labelName = null,
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = {
                    expanded = false
                    focusManager.clearFocus()
                },
            ) {
                currencies.forEachIndexed { index, currency ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                currencyDisplayName(currency.name),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        enabled = enabled,
                        onClick = {
                            onValueChange(currency)
                            expanded = false
                            focusManager.clearFocus()
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )

                    if (index < currencies.lastIndex) {
                        HorizontalDivider(thickness = DividerDefaults.Thickness)
                    }
                }
            }
        }
    }
}
