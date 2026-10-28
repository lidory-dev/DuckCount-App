package app.lidory.duckcount.android.ui.text

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Shows a field's text lowercased while it holds the text as typed, for a username, which is
 * checked and sent lowercased: lowercasing the text itself as it is typed would have the field
 * hand the keyboard a new text at every capital, and drop the keys already on their way to it.
 *
 * One character for one, so that every position of the cursor stays where it is: a character's
 * [Char.lowercaseChar] is always one character, where [String.lowercase] can make two of some
 * outside the ASCII letters a username is made of.
 */
object LowercaseVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(
            text = AnnotatedString(buildString { text.text.forEach { append(it.lowercaseChar()) } }),
            offsetMapping = OffsetMapping.Identity,
        )
}
