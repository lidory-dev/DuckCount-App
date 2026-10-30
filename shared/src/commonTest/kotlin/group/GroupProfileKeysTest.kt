package group

import contact.ProfileKeyUpdate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"
private const val CAROL = "33333333-3333-3333-3333-333333333333"
private const val DAVE = "44444444-4444-4444-4444-444444444444"

/**
 * What the creator of a group writes of its members' profile keys, in the Welcome and in the
 * message to the members already in, and what a member writes of their own, as the members' devices
 * read it back.
 */
class GroupProfileKeysTest {

    private val groupKey = ByteArray(32) { 1 }
    private val aliceKey = ByteArray(32) { 2 }
    private val bobKey = ByteArray(32) { 3 }

    @Test
    fun `the Welcome payload is written with the names the members read`() {
        val payload = Json.parseToJsonElement(
            welcomePayloadOf(groupKey = groupKey, keyGeneration = 3, profileKeys = mapOf(ALICE to aliceKey))
                .decodeToString(),
        ).jsonObject

        assertEquals(setOf("group_key", "key_generation", "profile_keys"), payload.keys)
        assertEquals("3", payload.getValue("key_generation").toString())
        assertEquals(setOf(ALICE), payload.getValue("profile_keys").jsonObject.keys)
    }

    @Test
    fun `the Welcome payload reads back as the group key of its generation and every member's key`() {
        val payload = welcomePayloadOf(
            groupKey = groupKey,
            keyGeneration = 3,
            profileKeys = mapOf(ALICE to aliceKey, BOB to bobKey),
        )
        val handover = assertNotNull(welcomeHandoverOf(payload))

        assertContentEquals(groupKey, handover.groupKey.key)
        assertEquals(3L, handover.groupKey.generation)
        assertEquals(setOf(ALICE, BOB), handover.profileKeys.keys)
        assertContentEquals(aliceKey, handover.profileKeys[ALICE])
        assertContentEquals(bobKey, handover.profileKeys[BOB])
    }

    /** As a Welcome handing over no key. */
    @Test
    fun `a Welcome payload that is not one reads as nothing`() {
        val key = Base64.Default.encode(groupKey)

        assertNull(welcomeHandoverOf(groupKey))
        assertNull(
            welcomeHandoverOf("""{"group_key":"not Base64!","key_generation":1,"profile_keys":{}}""".encodeToByteArray()),
        )
        assertNull(welcomeHandoverOf("""{"group_key":"$key","key_generation":1}""".encodeToByteArray()))
        assertNull(welcomeHandoverOf("""{"group_key":"$key","profile_keys":{}}""".encodeToByteArray()))
        assertNull(welcomeHandoverOf("""{"group_key":"$key","key_generation":0,"profile_keys":{}}""".encodeToByteArray()))
    }

    /** Whatever the creator wrote: only a 32-byte key opens a profile. */
    @Test
    fun `entries that are no profile key are left out and the others kept`() {
        val payload = """{"group_key":"${Base64.Default.encode(groupKey)}","key_generation":1,"profile_keys":{""" +
            """"$ALICE":"${Base64.Default.encode(aliceKey)}",""" +
            """"$BOB":"${Base64.Default.encode(bobKey + 0)}",""" +
            """"$CAROL":"not Base64!"}}"""

        val handover = assertNotNull(welcomeHandoverOf(payload.encodeToByteArray()))

        assertEquals(setOf(ALICE), handover.profileKeys.keys)
    }

    @Test
    fun `the keys handed to the members already in read back as such and as nothing else`() {
        val message = profileKeysMessageOf(profileKeys = mapOf(ALICE to aliceKey, BOB to bobKey))

        assertEquals(
            setOf("type", "profile_encryption_keys"),
            Json.parseToJsonElement(message.decodeToString()).jsonObject.keys,
        )

        val read = assertNotNull(profileKeysOf(message))

        assertEquals(setOf(ALICE, BOB), read.keys)
        assertContentEquals(bobKey, read[BOB])
        assertNull(groupKeyOf(message))
        assertNull(ownProfileKeyOf(message))
    }

    @Test
    fun `a member's own key reads back as theirs and as nothing else`() {
        val message = ProfileKeyUpdate(profileEncryptionKey = bobKey).serialize()

        assertContentEquals(bobKey, ownProfileKeyOf(message))
        assertNull(profileKeysOf(message))
        assertNull(groupKeyOf(message))
    }

    @Test
    fun `a member's own key that is no profile key is not read`() {
        assertNull(ownProfileKeyOf(ProfileKeyUpdate(profileEncryptionKey = bobKey + 0).serialize()))
    }

    @Test
    fun `the group's key handed over is no profile key`() {
        val message = groupKeyMessageOf(groupKey, keyGeneration = 2)

        assertNull(profileKeysOf(message))
        assertNull(ownProfileKeyOf(message))
    }

    /** Dave is no longer a contact, and was learned through a group; Carol neither. */
    @Test
    fun `the creator hands his own key and each member's as a contact or as learned and leaves out the rest`() =
        runBlocking<Unit> {
            val daveKey = ByteArray(32) { 4 }

            val handedOut = profileKeysToHandOut(
                memberUuids = listOf(ALICE, BOB, CAROL, DAVE),
                selfUuid = ALICE,
                ownProfileKey = aliceKey,
                contactProfileKey = { mapOf(BOB to bobKey)[it] },
                learnedProfileKey = { mapOf(DAVE to daveKey, BOB to ByteArray(32))[it] },
            )

            assertEquals(setOf(ALICE, BOB, DAVE), handedOut.keys)
            assertContentEquals(aliceKey, handedOut[ALICE])
            assertContentEquals(bobKey, handedOut[BOB])
            assertContentEquals(daveKey, handedOut[DAVE])
        }

    @Test
    fun `the creator's own key the vault did not give is left out`() = runBlocking<Unit> {
        val handedOut = profileKeysToHandOut(
            memberUuids = listOf(ALICE, BOB),
            selfUuid = ALICE,
            ownProfileKey = null,
            contactProfileKey = { bobKey },
            learnedProfileKey = { null },
        )

        assertEquals(setOf(BOB), handedOut.keys)
        assertTrue(ALICE !in handedOut)
    }
}
