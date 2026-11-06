import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Test

class RegistrationConsentTest {

    private val terms = TextLink(text = "terms of use", url = "https://example.com/terms")
    private val privacy = TextLink(text = "privacy policy", url = "https://example.com/privacy")

    /** Each link's text, as it reads in [text], beside the url it opens. */
    private fun linksOf(text: AnnotatedString): List<Pair<String, String>> =
        text.getLinkAnnotations(0, text.length).map {
            text.text.substring(it.start, it.end) to (it.item as LinkAnnotation.Url).url
        }

    @Test
    fun `each placeholder becomes the text of its link`() {
        val text = linkedText(
            template = "By registering, you accept the %1\$s and the %2\$s.",
            links = listOf(terms, privacy),
            styles = null,
        )

        assertEquals("By registering, you accept the terms of use and the privacy policy.", text.text)
        assertEquals(
            listOf(
                "terms of use" to "https://example.com/terms",
                "privacy policy" to "https://example.com/privacy",
            ),
            linksOf(text),
        )
    }

    @Test
    fun `a translation may put the placeholders in another order`() {
        val text = linkedText(
            template = "%2\$s, then %1\$s",
            links = listOf(terms, privacy),
            styles = null,
        )

        assertEquals("privacy policy, then terms of use", text.text)
        assertEquals(
            listOf(
                "privacy policy" to "https://example.com/privacy",
                "terms of use" to "https://example.com/terms",
            ),
            linksOf(text),
        )
    }
}
