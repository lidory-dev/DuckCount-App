package app.lidory.duckcount.android.ui.text

import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals

/** A username shown lowercased, see [LowercaseVisualTransformation]. */
class LowercaseVisualTransformationTest {

    @Test
    fun `a username typed with capitals is shown lowercased`() {
        assertEquals("ada4", LowercaseVisualTransformation.filter(AnnotatedString("AdA4")).text.text)
    }

    /** "İ" lowercases to two characters as a string, which would move every position after it. */
    @Test
    fun `every character is shown as one so the cursor keeps its place`() {
        val shown = LowercaseVisualTransformation.filter(AnnotatedString("Aİb"))

        assertEquals(3, shown.text.length)
        assertEquals(2, shown.offsetMapping.originalToTransformed(2))
        assertEquals(3, shown.offsetMapping.transformedToOriginal(3))
    }
}
