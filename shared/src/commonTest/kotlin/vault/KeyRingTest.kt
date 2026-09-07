package vault

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyRingTest {

    private val key = byteArrayOf(1, 2, 3, 4, 5)

    @Test
    fun `an empty key ring carries no key`() {
        assertNull(KeyRing.EMPTY.keyFor(part = VaultEntryKey.PROFILE_ENCRYPTION_KEY))
    }

    @Test
    fun `a key added to the key ring reads back unchanged`() {
        val keyRing = KeyRing.EMPTY.with(part = VaultEntryKey.PROFILE_ENCRYPTION_KEY, key = key)

        assertContentEquals(key, keyRing.keyFor(part = VaultEntryKey.PROFILE_ENCRYPTION_KEY))
    }

    @Test
    fun `a key ring survives being sealed and read back`() {
        val keyRing = KeyRing.EMPTY.with(part = VaultEntryKey.PROFILE_ENCRYPTION_KEY, key = key)

        val reread = KeyRing.parse(plaintext = keyRing.serialize())

        assertContentEquals(key, reread?.keyFor(part = VaultEntryKey.PROFILE_ENCRYPTION_KEY))
    }

    @Test
    fun `adding a key to a part replaces the one it held`() {
        val replacement = byteArrayOf(9, 9, 9)

        val keyRing = KeyRing.EMPTY
            .with(part = VaultEntryKey.PROFILE_ENCRYPTION_KEY, key = key)
            .with(part = VaultEntryKey.PROFILE_ENCRYPTION_KEY, key = replacement)

        assertContentEquals(
            replacement,
            keyRing.keyFor(part = VaultEntryKey.PROFILE_ENCRYPTION_KEY),
        )
    }

    @Test
    fun `keys of other parts are left alone`() {
        val groupKey = byteArrayOf(7, 7)

        val keyRing = KeyRing.EMPTY
            .with(part = VaultEntryKey.GROUP_MASTER_KEY, key = groupKey)
            .with(part = VaultEntryKey.PROFILE_ENCRYPTION_KEY, key = key)

        assertContentEquals(groupKey, keyRing.keyFor(part = VaultEntryKey.GROUP_MASTER_KEY))
    }

    /**
     * The rule the whole two-layer vault exists to protect, a key a newer app version
     * added must come back out of an older version's write. Dropping it here would
     * orphan that part's entries for good, since nothing else holds the key to them.
     */
    @Test
    fun `an entry this build does not know survives a write`() {
        val unknown = Base64.Default.encode(byteArrayOf(4, 2))
        val plaintext = """{"a_part_added_later":"$unknown"}""".encodeToByteArray()

        val rewritten = KeyRing.parse(plaintext = plaintext)!!
            .with(part = VaultEntryKey.PROFILE_ENCRYPTION_KEY, key = key)
            .serialize()
            .decodeToString()

        assertTrue(
            rewritten.contains("\"a_part_added_later\":\"$unknown\""),
            "the unknown entry was dropped: $rewritten",
        )
    }

    @Test
    fun `an entry that is not a Base64 string reads as no key`() {
        val plaintext = """{"profile_encryption_key":{"nested":"value"}}""".encodeToByteArray()

        val keyRing = KeyRing.parse(plaintext = plaintext)!!

        assertNull(keyRing.keyFor(part = VaultEntryKey.PROFILE_ENCRYPTION_KEY))
    }

    @Test
    fun `plaintext that is not a JSON object does not parse`() {
        assertNull(KeyRing.parse(plaintext = "not a key ring".encodeToByteArray()))
        assertNull(KeyRing.parse(plaintext = "[]".encodeToByteArray()))
        assertNull(KeyRing.parse(plaintext = byteArrayOf()))
    }

    @Test
    fun `every part is named the way the server names it`() {
        assertEquals(
            listOf(
                "root_key",
                "key_ring",
                "group_master_key",
                "profile_encryption_key",
                "blocked_users",
            ),
            VaultEntryKey.entries.map { it.wireName },
        )

        VaultEntryKey.entries.forEach { part ->
            assertEquals(part, VaultEntryKey.parse(wireName = part.wireName))
        }

        assertNull(VaultEntryKey.parse(wireName = "a_part_added_later"))
    }
}
