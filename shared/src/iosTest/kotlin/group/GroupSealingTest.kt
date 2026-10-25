package group

import app.lidory.duckcount.GroupLimits
import database.domain.CurrencyCatalog
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val OTHER_GROUP = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

/**
 * What a group's fields, sealed by its creator, open to: what was sealed, as that field of that
 * group under that key, and nothing else.
 */
class GroupSealingTest {

    private val groupKey = generateSymmetricEncryptionKey()

    private val currency = CurrencyCatalog.defaultCurrencies.last()

    private val sealed = assertNotNull(
        GroupFields(title = "Ski trip", description = "Chalet and lift passes", currency = currency)
            .seal(groupKey = groupKey, groupUuid = GROUP),
    )

    @Test
    fun `the fields open under the group they were sealed for`() {
        val opened = assertNotNull(sealed.unseal(groupKey = groupKey, groupUuid = GROUP))

        assertEquals("Ski trip", opened.title)
        assertEquals("Chalet and lift passes", opened.description)
        assertEquals(currency, opened.currency)
    }

    /** As a server serving one group's fields under another group's UUID would leave a member. */
    @Test
    fun `the fields do not open as another group's`() {
        assertNull(sealed.unseal(groupKey = groupKey, groupUuid = OTHER_GROUP))
    }

    @Test
    fun `a field does not open as another field`() {
        val swapped = SealedGroupFields(
            title = sealed.description,
            description = sealed.title,
            currency = sealed.currency,
        )

        assertNull(swapped.unseal(groupKey = groupKey, groupUuid = GROUP))
    }

    @Test
    fun `the fields do not open under another key`() {
        assertNull(sealed.unseal(groupKey = generateSymmetricEncryptionKey(), groupUuid = GROUP))
    }

    /** Only the catalogue's UUID is sealed, which every currency's is the same length of. */
    @Test
    fun `every currency is sealed to the same length`() {
        val lengths = CurrencyCatalog.defaultCurrencies.mapTo(HashSet()) { currency ->
            val fields = GroupFields(title = "", description = "", currency = currency)

            assertNotNull(fields.seal(groupKey = groupKey, groupUuid = GROUP)).currency.ciphertext.length
        }

        assertEquals(1, lengths.size)
    }

    /** One the screens could not lay out, sealed by a creator whose app does not hold to the form. */
    @Test
    fun `a title longer than the form allows does not open`() {
        val tooLong = assertNotNull(
            GroupFields(title = "a".repeat(GroupLimits.MAX_TITLE_LENGTH + 1), description = "", currency = currency)
                .seal(groupKey = groupKey, groupUuid = GROUP),
        )

        assertNull(tooLong.unseal(groupKey = groupKey, groupUuid = GROUP))
    }

    @Test
    fun `a description longer than the form allows does not open`() {
        val tooLong = assertNotNull(
            GroupFields(
                title = "",
                description = "a".repeat(GroupLimits.MAX_DESCRIPTION_LENGTH + 1),
                currency = currency,
            ).seal(groupKey = groupKey, groupUuid = GROUP),
        )

        assertNull(tooLong.unseal(groupKey = groupKey, groupUuid = GROUP))
    }

    /** As a creator on a release whose catalogue has currencies this one does not would leave it. */
    @Test
    fun `a currency the app does not ship with does not open`() {
        val unknown = CurrencyCatalog.defaultCurrency.copy(uuid = "33333333-3333-3333-3333-333333333333")

        val sealed = assertNotNull(
            GroupFields(title = "Ski trip", description = "", currency = unknown)
                .seal(groupKey = groupKey, groupUuid = GROUP),
        )

        assertNull(sealed.unseal(groupKey = groupKey, groupUuid = GROUP))
    }
}
