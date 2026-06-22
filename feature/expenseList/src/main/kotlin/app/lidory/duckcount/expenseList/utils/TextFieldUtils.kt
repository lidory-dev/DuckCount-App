package app.lidory.duckcount.expenseList.utils

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

class CurrencySymbolTransformation(val currencySymbol: String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val outputText = text.text + currencySymbol

        val annotatedOutput = AnnotatedString(outputText)

        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                return minOf(offset, text.length)
            }

            override fun transformedToOriginal(offset: Int): Int {
                return minOf(offset, text.length)
            }
        }

        return TransformedText(annotatedOutput, offsetMapping)
    }
}
