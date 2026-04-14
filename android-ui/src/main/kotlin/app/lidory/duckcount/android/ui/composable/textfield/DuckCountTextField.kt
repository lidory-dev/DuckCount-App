package app.lidory.duckcount.android.ui.composable.textfield

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.lidory.duckcount.android.ui.constant.Padding

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuckCountTextField(
    value: String,
    onValueChange: (String) -> Unit,
    focusManager: FocusManager,
    modifier: Modifier = Modifier,
    isInError: Boolean = false,
    error: String? = "",
    placeholder: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    textStyle: TextStyle = TextStyle.Default,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 5,
    minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    cursorBrush: Brush = SolidColor(Color.Black),
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

    val borderWidth by animateDpAsState(
        targetValue = if (isFocused) TextFieldDefaults.FocusedIndicatorThickness else 0.dp,
        animationSpec = tween(200),
    )

    val borderColor by animateColorAsState(
        targetValue = if (isFocused) TextFieldDefaults.colors().focusedIndicatorColor else Color.Transparent,
        animationSpec = tween(200),
    )

    BasicTextField(
        modifier = modifier
            .border(
                width = borderWidth,
                color = borderColor,
                shape = RoundedCornerShape(Padding.large),
            )
            .background(
                color = TextFieldDefaults.colors().unfocusedContainerColor,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(Padding.large),
            ),
        value = value,
        enabled = enabled,
        onValueChange = onValueChange,
        interactionSource = interactionSource,
        visualTransformation = visualTransformation,
        onTextLayout = onTextLayout,
        maxLines = maxLines,
        minLines = minLines,
        singleLine = singleLine,
        readOnly = readOnly,
        keyboardOptions = keyboardOptions,
        textStyle = textStyle.copy(color = colors.unfocusedTextColor),
        keyboardActions = keyboardActions,
        cursorBrush = cursorBrush,
        decorationBox = { innerTextField ->
            TextFieldDefaults.DecorationBox(
                isError = isInError,
                enabled = true,
                value = value,
                placeholder = placeholder,
                visualTransformation = visualTransformation,
                singleLine = singleLine,
                interactionSource = interactionSource,
                contentPadding = TextFieldDefaults.contentPaddingWithLabel(),
                trailingIcon = trailingIcon,
                leadingIcon = leadingIcon,
                innerTextField = {
                    Column(
                        modifier = innerModifier
                            .animateContentSize(
                                animationSpec = spring(),
                            ),
                        verticalArrangement = Arrangement.spacedBy(
                            Padding.small,
                        ),
                    ) {
                        labelName?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.unfocusedLabelColor,
                            )
                        }
                        innerTextField()
                        AnimatedVisibility(
                            enter = slideInVertically(
                                animationSpec = tween(),
                                initialOffsetY = { it },
                            ),
                            exit = shrinkVertically(
                                shrinkTowards = Alignment.Top,
                            ),
                            visible = isInError,
                        ) {

                            Text(
                                text = error.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextFieldDefaults.colors().errorLabelColor,
                            )
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
        },
    )
}