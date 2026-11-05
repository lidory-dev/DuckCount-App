package group

import app.lidory.duckcount.GroupLimits
import database.data.remote.EncryptedFieldDto
import database.domain.CurrencyCatalog
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import vault.sealBound
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        val opened = assertNotNull(sealed.unseal(groupKey = groupKey, currencyKey = groupKey, groupUuid = GROUP))

        assertEquals("Ski trip", opened.title)
        assertEquals("Chalet and lift passes", opened.description)
        assertEquals(currency, opened.currency)
    }

    /** As a server serving one group's fields under another group's UUID would leave a member. */
    @Test
    fun `the fields do not open as another group's`() {
        assertNull(sealed.unseal(groupKey = groupKey, currencyKey = groupKey, groupUuid = OTHER_GROUP))
    }

    @Test
    fun `a field does not open as another field`() {
        val swapped = SealedGroupFields(
            title = sealed.description,
            description = sealed.title,
            currency = sealed.currency,
        )

        assertNull(swapped.unseal(groupKey = groupKey, currencyKey = groupKey, groupUuid = GROUP))
    }

    @Test
    fun `the fields do not open under another key`() {
        val anotherKey = generateSymmetricEncryptionKey()

        assertNull(sealed.unseal(groupKey = anotherKey, currencyKey = groupKey, groupUuid = GROUP))
        assertNull(sealed.unseal(groupKey = groupKey, currencyKey = anotherKey, groupUuid = GROUP))
    }

    /**
     * An edit made in a later epoch: its title, description and signature under that epoch's key,
     * the currency as it was sealed at the creation, under the first.
     */
    @Test
    fun `a version edited in a later epoch opens with the currency under the first generation`() {
        val laterKey = generateSymmetricEncryptionKey()
        val edited = sealedVersion(groupKey = laterKey)
        val listed = SealedGroupVersion(
            fields = SealedGroupFields(
                title = edited.fields.title,
                description = edited.fields.description,
                currency = sealed.currency,
            ),
            revision = edited.revision,
            signature = edited.signature,
        )

        val opened = assertNotNull(listed.unseal(groupKey = laterKey, currencyKey = groupKey, groupUuid = GROUP))

        assertEquals(currency, opened.fields.currency)
        assertTrue(listed.opensUnder(groupKey = laterKey, groupUuid = GROUP))
        assertFalse(listed.opensUnder(groupKey = groupKey, groupUuid = GROUP))
        assertNull(listed.unseal(groupKey = groupKey, currencyKey = groupKey, groupUuid = GROUP))
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

    private fun EncryptedFieldDto.sealedSize(): Int = Base64.Default.decode(ciphertext).size

    /** [text] sealed as the field [field] of the group, as a creator's app of its own would. */
    private fun sealedAs(field: String, text: String): EncryptedFieldDto =
        assertNotNull(sealBound(key = groupKey, context = "group:$GROUP:$field", plaintext = text.encodeToByteArray()))

    private fun SealedGroupFields.opened() = unseal(groupKey = groupKey, currencyKey = groupKey, groupUuid = GROUP)

    /** Sealing hides what a title says but not how long it is, which would say something of it as well. */
    @Test
    fun `a title seals to the same length whatever it says`() {
        val most = GroupLimits.MAX_TITLE_LENGTH

        val titles = listOf(
            "",
            "a",
            "Ski trip",
            "a".repeat(most),
            // Two bytes each in UTF-8, three, and four for two characters.
            "é".repeat(most),
            "€".repeat(most),
            "😀".repeat(most / 2),
        )

        val sizes = titles.map { title ->
            val fields = assertNotNull(
                GroupFields(title = title, description = "", currency = currency).seal(groupKey = groupKey, groupUuid = GROUP),
            )

            assertEquals(title, assertNotNull(fields.opened(), title).title)

            fields.title.sealedSize()
        }

        assertEquals(setOf(most * 3 + 16), sizes.toSet())
    }

    /** Sealing hides what a description says but not how long it is, which would say something of it as well. */
    @Test
    fun `a description seals to the same length whatever it says`() {
        val most = GroupLimits.MAX_DESCRIPTION_LENGTH

        val descriptions = listOf(
            "",
            "a",
            "Chalet and lift passes",
            // Either side of the sizes an expense's description is padded to, which mean nothing here.
            "a".repeat(128),
            "a".repeat(129),
            "a".repeat(512),
            "a".repeat(513),
            "a".repeat(most),
            // Two bytes each in UTF-8, three, and four for two characters.
            "é".repeat(most),
            "€".repeat(most),
            "😀".repeat(most / 2),
        )

        val sizes = descriptions.map { description ->
            val fields = assertNotNull(
                GroupFields(title = "", description = description, currency = currency)
                    .seal(groupKey = groupKey, groupUuid = GROUP),
            )

            assertEquals(description, assertNotNull(fields.opened(), description).description)

            fields.description.sealedSize()
        }

        assertEquals(setOf(most * 3 + 16), sizes.toSet())
    }

    /** As a creator's app of its own would seal it: the padding alone is off. */
    @Test
    fun `a title not padded to the one size does not open`() {
        val size = GroupLimits.MAX_TITLE_LENGTH * 3

        fun withTitle(title: String) =
            SealedGroupFields(title = sealedAs("title", title), description = sealed.description, currency = sealed.currency).opened()

        // Padded as it should be, as a control.
        assertEquals("Ski trip", assertNotNull(withTitle("Ski trip".padEnd(size))).title)

        listOf("Ski trip", "Ski trip".padEnd(size - 1), "Ski trip".padEnd(size + 1)).forEach { title ->
            assertNull(withTitle(title), "${title.length} characters")
        }
    }

    /** As a creator's app of its own would seal it: the padding alone is off. */
    @Test
    fun `a description not padded to the one size does not open`() {
        val most = GroupLimits.MAX_DESCRIPTION_LENGTH * 3

        fun withDescription(description: String) =
            SealedGroupFields(title = sealed.title, description = sealedAs("description", description), currency = sealed.currency).opened()

        // Padded as it should be, as a control.
        assertEquals("Chalet", assertNotNull(withDescription("Chalet".padEnd(most))).description)

        // Not even at the sizes an expense's description is padded to.
        listOf(6, 128, 512, most - 1, most + 1).forEach { size ->
            assertNull(withDescription("Chalet".padEnd(size)), "$size bytes")
        }
    }

    /**
     * The creator signs what the fields open to, so that nothing but the padding is off, and a version
     * whose padding is off is refused whatever the signature says.
     */
    @Test
    fun `a version whose title is not padded does not open`() {
        val version = GroupVersion(
            fields = GroupFields(title = "Ski trip", description = "", currency = currency),
            revision = 1,
            signature = ByteArray(64),
        )

        val sealedVersion = assertNotNull(version.seal(groupKey = groupKey, groupUuid = GROUP))

        assertTrue(sealedVersion.opensUnder(groupKey = groupKey, groupUuid = GROUP))

        val unpadded = SealedGroupVersion(
            fields = SealedGroupFields(
                title = sealedAs("title", "Ski trip"),
                description = sealedVersion.fields.description,
                currency = sealedVersion.fields.currency,
            ),
            revision = sealedVersion.revision,
            signature = sealedVersion.signature,
        )

        assertFalse(unpadded.opensUnder(groupKey = groupKey, groupUuid = GROUP))
        assertNull(unpadded.unseal(groupKey = groupKey, currencyKey = groupKey, groupUuid = GROUP))
    }

    /** One the screens could not lay out, sealed by a creator whose app does not hold to the form. */
    @Test
    fun `a title longer than the form allows does not open`() {
        val tooLong = assertNotNull(
            GroupFields(title = "a".repeat(GroupLimits.MAX_TITLE_LENGTH + 1), description = "", currency = currency)
                .seal(groupKey = groupKey, groupUuid = GROUP),
        )

        assertNull(tooLong.unseal(groupKey = groupKey, currencyKey = groupKey, groupUuid = GROUP))
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

        assertNull(tooLong.unseal(groupKey = groupKey, currencyKey = groupKey, groupUuid = GROUP))
    }

    /** As a creator on a release whose catalogue has currencies this one does not would leave it. */
    @Test
    fun `a currency the app does not ship with does not open`() {
        val unknown = CurrencyCatalog.defaultCurrency.copy(uuid = "33333333-3333-3333-3333-333333333333")

        val sealed = assertNotNull(
            GroupFields(title = "Ski trip", description = "", currency = unknown)
                .seal(groupKey = groupKey, groupUuid = GROUP),
        )

        assertNull(sealed.unseal(groupKey = groupKey, currencyKey = groupKey, groupUuid = GROUP))
    }

    @Test
    fun `a version opens under the group it was sealed for with its revision and signature`() {
        val opened = assertNotNull(sealedVersion().unseal(groupKey = groupKey, currencyKey = groupKey, groupUuid = GROUP))

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

        assertNotNull(mixed.fields.unseal(groupKey = groupKey, currencyKey = groupKey, groupUuid = GROUP))
        assertNull(mixed.unseal(groupKey = groupKey, currencyKey = groupKey, groupUuid = GROUP))
    }

    @Test
    fun `a field does not open as the signature`() {
        val version = sealedVersion()
        val swapped = SealedGroupVersion(fields = version.fields, revision = 3, signature = version.fields.title)

        assertNull(swapped.unseal(groupKey = groupKey, currencyKey = groupKey, groupUuid = GROUP))
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

        assertNull(swapped.unseal(groupKey = groupKey, currencyKey = groupKey, groupUuid = GROUP))
    }

    /** All or nothing: fields that open with no signature that does are a version nobody can tell the writer of. */
    @Test
    fun `a version whose signature alone does not open does not open`() {
        val mixed = SealedGroupVersion(
            fields = sealedVersion().fields,
            revision = 3,
            signature = sealedVersion(groupKey = generateSymmetricEncryptionKey()).signature,
        )

        assertNotNull(mixed.fields.unseal(groupKey = groupKey, currencyKey = groupKey, groupUuid = GROUP))
        assertNull(mixed.unseal(groupKey = groupKey, currencyKey = groupKey, groupUuid = GROUP))
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
