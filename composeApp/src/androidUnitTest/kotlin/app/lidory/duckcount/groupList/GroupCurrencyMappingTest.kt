package app.lidory.duckcount.groupList

import database.domain.CurrencyCatalog
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a group is created with for a currency the form offered, see [toGroupListModel] and
 * [toDb]: the catalogue's own entry, whose UUID is all a group seals of it, and what every member
 * looks it up by.
 */
class GroupCurrencyMappingTest {

    /** The form once built its own currency from the name and symbol, under a UUID of its own. */
    @Test
    fun `every currency the form offers goes back as the catalogue entry it came from`() {
        CurrencyCatalog.defaultCurrencies.forEach { currency ->
            assertEquals(currency, currency.toGroupListModel().toDb())
        }
    }
}
