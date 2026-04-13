package component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import database.currency.Currency
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)
@Composable
fun CurrencyDropDownMenu(
    currency: String,
    onValueChange: (String, String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    focusManager: FocusManager,
) {
    // TODO: Change with real currency
    val currencies =
        listOf(
            Currency(uuid = Uuid.random().toString(), "EUR", "€", true),
            Currency(uuid = Uuid.random().toString(), "USD", "$", true),
            Currency(uuid = Uuid.random().toString(), "JPY", "¥", false),
        )

    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = modifier,
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = {
                expanded = !expanded
            },
        ) {
            CustomTextField(
                value = currency,
                onValueChange = { },
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
                error = "",
                isInError = currency.isEmpty(),
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
                currencies.forEach { currency ->
                    DropdownMenuItem(
                        text = { Text(currency.name, color = MaterialTheme.colorScheme.onSurface) },
                        onClick = {
                            onValueChange(currency.name, currency.symbol, currency.hasSubunits)
                            expanded = false
                            focusManager.clearFocus()
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
    }
}