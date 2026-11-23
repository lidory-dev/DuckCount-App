package app.lidory.duckcount.expenseList.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** An amount field's text as typed, and the amount it stands for. */
class AmountInputTest {

    @Test
    fun `digits and one separator with up to two decimals are taken as typed`() {
        listOf("", "12", "12.", "12,", "12.5", "12,50", ".5", "007").forEach {
            assertTrue(it, it.isAmountInput(hasSubunits = true))
        }
    }

    /** Refused rather than rounded or cleaned, which would rewrite what is being typed. */
    @Test
    fun `a third decimal a second separator or anything but digits is refused`() {
        listOf("12.505", "12.5.5", "12,5,", "1a", "-3", "1 2").forEach {
            assertFalse(it, it.isAmountInput(hasSubunits = true))
        }
    }

    @Test
    fun `a currency without subunits takes no separator`() {
        assertTrue("1200".isAmountInput(hasSubunits = false))
        assertFalse("12.".isAmountInput(hasSubunits = false))
        assertFalse("12,".isAmountInput(hasSubunits = false))
    }

    @Test
    fun `the amount a text stands for is written as the form keeps it`() {
        assertEquals("12.50", "12,5".toAmount(hasSubunits = true))
        assertEquals("12.00", "12.".toAmount(hasSubunits = true))
        assertEquals("0.50", ".5".toAmount(hasSubunits = true))
        assertEquals("12.00", "12".toAmount(hasSubunits = true))
        assertEquals("7.00", "007".toAmount(hasSubunits = true))
        assertEquals("1200", "1200".toAmount(hasSubunits = false))
    }

    @Test
    fun `nothing or a zero alone stands for no amount yet`() {
        assertEquals("", "".toAmount(hasSubunits = true))
        assertEquals("", "0".toAmount(hasSubunits = true))
    }

    @Test
    fun `deleting the separator drops the decimals with it`() {
        assertEquals("12", "1250".dropDecimalsIfSeparatorDeleted(previous = "12.50"))
        assertEquals("12", "125".dropDecimalsIfSeparatorDeleted(previous = "12,5"))
        assertEquals("12", "12".dropDecimalsIfSeparatorDeleted(previous = "12."))
    }

    @Test
    fun `any other edit is kept as it is`() {
        assertEquals("12.0", "12.0".dropDecimalsIfSeparatorDeleted(previous = "12.50"))
        assertEquals("1250", "1250".dropDecimalsIfSeparatorDeleted(previous = "125"))
        assertEquals("12.5", "12.5".dropDecimalsIfSeparatorDeleted(previous = "12."))
    }

    @Test
    fun `an amount is shown in its field with the decimal separator of the language`() {
        assertEquals("1234.50", "1234.50".toAmountFieldText(Locale.UK))
        assertEquals("1234,50", "1234.50".toAmountFieldText(Locale.FRANCE))
        assertEquals("1200", "1200".toAmountFieldText(Locale.FRANCE))
        assertEquals("", "".toAmountFieldText(Locale.FRANCE))
    }

    /** Grouped, or in other digits, the field would refuse its own text as soon as it is edited. */
    @Test
    fun `nothing else of how the language writes a number reaches the field`() {
        assertEquals("1234,50", "1234.50".toAmountFieldText(Locale.GERMANY))
        assertEquals("1234.50", "1234.50".toAmountFieldText(Locale.forLanguageTag("ar-EG")))
        assertEquals("1234.50", "1234.50".toAmountFieldText(Locale.forLanguageTag("de-CH")))
    }

    /** What the field shows stands for the very amount it was written from, whatever the language. */
    @Test
    fun `an amount shown in its field is taken back as the same amount`() {
        listOf(Locale.UK, Locale.FRANCE, Locale.forLanguageTag("ar-EG")).forEach { locale ->
            val shown = "1234.50".toAmountFieldText(locale)

            assertTrue(shown, shown.isAmountInput(hasSubunits = true))
            assertEquals("1234.50", shown.toAmount(hasSubunits = true))
        }
    }
}
