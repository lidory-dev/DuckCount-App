package app.lidory.duckcount.android.ui.composable.textfield

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.android.ui.constant.Padding
import app.lidory.duckcount.android.ui.text.limitedTo
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * A text field of the app, holding [value] and telling [onValueChange] of each edit.
 *
 * With a [maxLength], it stops there: of what an edit inserts past it only what fits is kept,
 * see [limitedTo], and an edit nothing of which fits is refused with the cursor left where it
 * was. [counter] shows how much of it is used.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuckCountTextField(
    value: String,
    onValueChange: (String) -> Unit,
    focusManager: FocusManager,
    modifier: Modifier = Modifier,
    isInError: Boolean = false,
    error: String? = "",
    counter: String? = null,
    maxLength: Int? = null,
    placeholder: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge.copy(
        color = MaterialTheme.colorScheme.onSurface,
    ),
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 5,
    minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    cursorBrush: Brush = SolidColor(MaterialTheme.colorScheme.onSurface),
    labelName: String? = null,
    readOnly: Boolean = false,
    innerModifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    colors: TextFieldColors = TextFieldDefaults.colors(),
    keyboardOptions: KeyboardOptions = KeyboardOptions(
        keyboardType = KeyboardType.Text,
        imeAction = ImeAction.Done,
    ),
    keyboardActions: KeyboardActions = KeyboardActions(
        onDone = { focusManager.clearFocus() },
    ),
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }

    val isFocused by interactionSource.collectIsFocusedAsState()

    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val textBringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    // The whole field if it fits, and its text rather than its label when it does not, the room
    // above the keyboard being shorter than the field in landscape for instance. All of the text
    // though, not just the line being typed, so text of several lines taller than that room can
    // still leave that line under the keyboard.
    suspend fun bringIntoView() {
        bringIntoViewRequester.bringIntoView()
        textBringIntoViewRequester.bringIntoView()
    }

    val ime = WindowInsets.ime
    val density = LocalDensity.current

    // Again as the keyboard opens or grows taller, which leaves less room than the field had when
    // it was focused. Not as it shrinks or closes, nor on every move of the field, so that the form
    // can still be scrolled while it is focused, to the button below it for instance.
    LaunchedEffect(isFocused) {
        if (!isFocused) return@LaunchedEffect

        var imeBottom = 0
        snapshotFlow { ime.getBottom(density) }.collectLatest { bottom ->
            val grows = bottom > imeBottom
            imeBottom = bottom
            if (grows) bringIntoView()
        }
    }

    val borderWidth by animateDpAsState(
        targetValue = if (isFocused) TextFieldDefaults.FocusedIndicatorThickness else 0.dp,
        animationSpec = tween(200),
    )

    val borderColor by animateColorAsState(
        targetValue = if (isFocused) TextFieldDefaults.colors().focusedIndicatorColor else Color.Transparent,
        animationSpec = tween(200),
    )

    val defaultColors = TextFieldDefaults.colors()

    val containerColor by animateColorAsState(
        targetValue = if (enabled) defaultColors.unfocusedContainerColor
        else defaultColors.disabledContainerColor,
        animationSpec = tween(200),
    )

    val resolvedTextStyle = textStyle.merge(
        TextStyle(
            color = when {
                !enabled -> defaultColors.disabledTextColor
                isInError -> defaultColors.errorTextColor
                else -> textStyle.color
            },
        ),
    )

    // The field's whole value, its cursor and composition along with its text, kept here as
    // BasicTextField keeps it for a String, but so that an edit [maxLength] refuses is undone
    // cursor and all: the text alone would come back with the cursor where the refused edit moved
    // it. Its text follows [value] whenever that changes from outside the field.
    var fieldValue by remember { mutableStateOf(TextFieldValue(text = value)) }
    val shownValue = fieldValue.copy(text = value)

    SideEffect {
        if (shownValue != fieldValue) fieldValue = shownValue
    }

    // The text [onValueChange] was last told of, or [value] last held, so that several edits in
    // one frame tell it of each text once.
    var lastText by remember(value) { mutableStateOf(value) }

    BasicTextField(
        modifier = modifier
            // Focusing brings the field into view only as it is then: brought in again as it grows
            // while being filled, by an error line or another line of text, which would otherwise
            // appear under the keyboard.
            .bringIntoViewRequester(bringIntoViewRequester)
            .onSizeChanged {
                if (isFocused) coroutineScope.launch { bringIntoView() }
            }
            .border(width = borderWidth, color = borderColor, shape = RoundedCornerShape(Padding.large))
            .background(color = containerColor, shape = RoundedCornerShape(Padding.large)),
        value = shownValue,
        enabled = enabled,
        onValueChange = { edited ->
            // Against what the field last kept, which an edit earlier in the frame may have moved
            // on from what it shows.
            val kept = maxLength?.let { edited.limitedTo(maxLength = it, previous = fieldValue) } ?: edited
            fieldValue = kept

            if (kept.text != lastText) {
                lastText = kept.text
                onValueChange(kept.text)
            }
        },
        interactionSource = interactionSource,
        visualTransformation = visualTransformation,
        onTextLayout = onTextLayout,
        maxLines = maxLines,
        minLines = minLines,
        singleLine = singleLine,
        readOnly = readOnly,
        keyboardOptions = keyboardOptions,
        textStyle = resolvedTextStyle,
        keyboardActions = keyboardActions,
        cursorBrush = cursorBrush,
    ) { innerTextField ->
        TextFieldDefaults.DecorationBox(
            isError = isInError,
            enabled = enabled,
            value = value,
            placeholder = placeholder,
            visualTransformation = visualTransformation,
            singleLine = singleLine,
            interactionSource = interactionSource,
            contentPadding = DuckCountTextFieldDefault.contentPaddingWithLabel(),
            trailingIcon = trailingIcon,
            leadingIcon = leadingIcon,
            innerTextField = {
                InnerTextField(
                    labelName = labelName,
                    modifier = innerModifier,
                    error = error,
                    isInError = isInError,
                    counter = counter,
                    colors = colors,
                ) {
                    Box(modifier = Modifier.bringIntoViewRequester(textBringIntoViewRequester)) {
                        innerTextField()
                    }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                errorIndicatorColor = Color.Transparent,
                focusedTextColor = TextFieldDefaults.colors().focusedTextColor,
                unfocusedTextColor = TextFieldDefaults.colors().unfocusedTextColor,
                selectionColors = TextSelectionColors(
                    handleColor = TextFieldDefaults.colors().textSelectionColors.handleColor,
                    backgroundColor = TextFieldDefaults.colors().textSelectionColors.backgroundColor,
                ),
            ),
        )
    }
}
