package group

import app.lidory.duckcount.GroupLimits
import database.domain.CurrencyCatalog
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val OTHER_GROUP = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

/**
 * What a group's fields, sealed by its creator, open to: what was sealed, as that field of that
 * group under that key, and nothing else. So does the signature over a version of them, sealed as
 * one more field, see [GroupVersion.seal].
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

    @Test
    fun `a version opens under the group it was sealed for with its revision and signature`() {
        val opened = assertNotNull(sealedVersion().unseal(groupKey = groupKey, groupUuid = GROUP))

        assertEquals("Ski trip", opened.fields.title)
        assertEquals(currency, opened.fields.currency)
        assertEquals(3L, opened.revision)
        assertContentEquals(SIGNATURE, opened.signature)
    }

    /**
     * As a server serving one group's signature with another's fields would leave a member, the
     * two groups under one key: the fields open, as this group's, and the signature does not.
     */
    @Test
    fun `a version whose signature was sealed for another group does not open`() {
        val elsewhere = sealedVersion(groupUuid = OTHER_GROUP)
        val mixed = SealedGroupVersion(
            fields = sealedVersion().fields,
            revision = 3,
            signature = elsewhere.signature,
        )

        assertNotNull(mixed.fields.unseal(groupKey = groupKey, groupUuid = GROUP))
        assertNull(mixed.unseal(groupKey = groupKey, groupUuid = GROUP))
    }

    @Test
    fun `a field does not open as the signature`() {
        val version = sealedVersion()
        val swapped = SealedGroupVersion(fields = version.fields, revision = 3, signature = version.fields.title)

        assertNull(swapped.unseal(groupKey = groupKey, groupUuid = GROUP))
    }

    @Test
    fun `the signature does not open as a field`() {
        val version = sealedVersion()
        val swapped = SealedGroupVersion(
            fields = SealedGroupFields(
                title = version.signature,
                description = version.fields.description,
                currency = version.fields.currency,
            ),
            revision = 3,
            signature = version.signature,
        )

        assertNull(swapped.unseal(groupKey = groupKey, groupUuid = GROUP))
    }

    /** All or nothing: fields that open with no signature that does are a version nobody can tell the writer of. */
    @Test
    fun `a version whose signature alone does not open does not open`() {
        val mixed = SealedGroupVersion(
            fields = sealedVersion().fields,
            revision = 3,
            signature = sealedVersion(groupKey = generateSymmetricEncryptionKey()).signature,
        )

        assertNotNull(mixed.fields.unseal(groupKey = groupKey, groupUuid = GROUP))
        assertNull(mixed.unseal(groupKey = groupKey, groupUuid = GROUP))
    }

    /**
     * The fields [sealed] holds, as revision 3 with [SIGNATURE], sealed under [groupKey] for
     * [groupUuid]. Sealing takes the signature as it is, whoever made it, see [GroupVersion.seal].
     */
    private fun sealedVersion(groupKey: ByteArray = this.groupKey, groupUuid: String = GROUP): SealedGroupVersion {
        val version = GroupVersion(
            fields = GroupFields(title = "Ski trip", description = "Chalet and lift passes", currency = currency),
            revision = 3,
            signature = SIGNATURE,
        )

        return assertNotNull(version.seal(groupKey = groupKey, groupUuid = groupUuid))
    }
}

/** What stands for a creator's signature: sealing reads nothing of it, see [GroupVersion.seal]. */
private val SIGNATURE = ByteArray(64) { it.toByte() }
