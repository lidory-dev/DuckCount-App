package app.lidory.duckcount.android.ui.text

import java.math.BigDecimal
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How every screen writes an amount, see [formatAmount]: as the user's language writes it, as iOS
 * does, the library's own way of writing it left to what is kept, sent and sealed.
 */
class AmountTextTest {

    private val english = Locale.UK
    private val french = Locale.FRANCE

    private fun euros(amount: String, locale: Locale, withSign: Boolean = false) =
        formatAmount(
            amount = amount,
            currencyCode = "EUR",
            symbol = "€",
            hasSubunits = true,
            withSign = withSign,
            locale = locale,
        ).withPlainSpaces()

    // French writes a narrow no-break space between thousands and a no-break space before the
    // symbol, which the versions of the locale data differ on: what matters here is where they are.
    private fun String.withPlainSpaces() = replace(' ', ' ').replace(' ', ' ')

    @Test
    fun `an amount is written as the language writes it`() {
        assertEquals("€1,234.50", euros("1234.50", english))
        assertEquals("1 234,50 €", euros("1234.50", french))
    }

    /** The balances, which go below zero. */
    @Test
    fun `a negative amount keeps its sign where the language puts it`() {
        assertEquals("-€98.67", euros("-98.67", english))
        assertEquals("-98,67 €", euros("-98.67", french))
    }

    /** What the user gets back of an expense, beside what they owe of another. */
    @Test
    fun `a positive amount written with its sign gets a plus where a minus would go`() {
        assertEquals("+€48.00", euros("48.00", english, withSign = true))
        assertEquals("+48,00 €", euros("48.00", french, withSign = true))
        assertEquals("-€18.00", euros("-18.00", english, withSign = true))
    }

    @Test
    fun `an amount keeps the decimals of its currency`() {
        assertEquals("€12.50", euros("12.5", english))
        assertEquals(
            "¥1,200",
            formatAmount(amount = "1200", currencyCode = "JPY", symbol = "¥", hasSubunits = false, locale = Locale.US),
        )
    }

    @Test
    fun `a number works as well as the library's text`() {
        assertEquals(
            "-18,00 €",
            formatAmount(
                value = BigDecimal("18.00").negate(),
                currencyCode = "EUR",
                symbol = "€",
                hasSubunits = true,
                locale = french,
            ).withPlainSpaces(),
        )
    }

    @Test
    fun `what is no amount or no currency is written as it is next to its symbol`() {
        assertEquals("abc €", euros("abc", english))
        assertEquals(
            "12.50 Fr",
            formatAmount(amount = "12.50", currencyCode = "Franc", symbol = "Fr", hasSubunits = true, locale = english),
        )
    }
}
