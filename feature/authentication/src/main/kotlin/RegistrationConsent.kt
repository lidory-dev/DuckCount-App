import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import app.lidory.duckcount.authentication.R

/**
 * Says that creating an account accepts the terms of use and the privacy policy, each opening
 * in the browser, under the registration form.
 */
@Composable
fun RegistrationConsent(
    termsOfUseLink: String,
    privacyPolicyLink: String,
    modifier: Modifier = Modifier,
) {
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        ),
    )

    Text(
        modifier = modifier,
        text = linkedText(
            template = stringResource(R.string.registration_consent),
            links = listOf(
                TextLink(
                    text = stringResource(R.string.registration_consent_terms_of_use),
                    url = termsOfUseLink,
                ),
                TextLink(
                    text = stringResource(R.string.registration_consent_privacy_policy),
                    url = privacyPolicyLink,
                ),
            ),
            styles = linkStyles,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** A link's [text], put in place of a placeholder, which opens [url]. */
data class TextLink(val text: String, val url: String)

private val PLACEHOLDER = Regex("%(\\d)\\\$s")

/**
 * [template] with each `%n$s` replaced by the text of the nth of [links], made a link to its url:
 * the placeholders are found wherever a translation puts them, in whatever order.
 */
fun linkedText(
    template: String,
    links: List<TextLink>,
    styles: TextLinkStyles?,
): AnnotatedString = buildAnnotatedString {
    var position = 0

    PLACEHOLDER.findAll(template).forEach { placeholder ->
        append(template.substring(position, placeholder.range.first))

        val link = links[placeholder.groupValues[1].toInt() - 1]
        withLink(LinkAnnotation.Url(url = link.url, styles = styles)) {
            append(link.text)
        }

        position = placeholder.range.last + 1
    }

    append(template.substring(position))
}
