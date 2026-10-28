package app.lidory.duckcount.expenseList.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals("7", "007".toAmount(hasSubunits = true))
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
}
