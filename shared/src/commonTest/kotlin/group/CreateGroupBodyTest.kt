package group

import database.data.remote.EncryptedFieldDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val GROUP = "aaaaaaaa-aaaa-8aaa-8aaa-aaaaaaaaaaaa"

/** How a group creation is written for the server, and kept until it answers. */
class CreateGroupBodyTest {

    /** As the client decodes every body. */
    private val json = Json { ignoreUnknownKeys = true }

    private val body = CreateGroupBody(
        nonce = "AAECAwQFBgcICQoLDA0ODw==",
        title = EncryptedFieldDto(ciphertext = "dGl0bGU=", nonce = "bm9uY2Ux"),
        description = EncryptedFieldDto(ciphertext = "ZGVzY3JpcHRpb24=", nonce = "bm9uY2Uy"),
        currency = EncryptedFieldDto(ciphertext = "Y3VycmVuY3k=", nonce = "bm9uY2Uz"),
        signature = EncryptedFieldDto(ciphertext = "c2lnbmF0dXJl", nonce = "bm9uY2U1"),
        welcomeMessage = "d2VsY29tZQ==",
        invitedMemberUuids = listOf("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
        keyCopy = EncryptedFieldDto(ciphertext = "a2V5", nonce = "bm9uY2U0"),
    )

    @Test
    fun `a creation is written with the names the server reads`() {
        val written = Json.parseToJsonElement(pendingCreationOf(groupUuid = GROUP, body = body).body).jsonObject

        assertEquals(
            setOf(
                "nonce",
                "title",
                "description",
                "currency",
                "signature",
                "welcome_message",
                "invited_member_uuids",
                "key_copy",
            ),
            written.keys,
        )
    }

    /**
     * Sent again exactly as it first went out, the creator's copy of the key included, see
     * `PendingGroupCreationEntity`.
     */
    @Test
    fun `a creation kept pending is sent again as it was`() {
        val pending = pendingCreationOf(groupUuid = GROUP, body = body)

        assertEquals(GROUP, pending.groupUuid)
        assertEquals(body, pending.toBody())
    }

    @Test
    fun `the 410 names every invitee with no account`() {
        val response = json.decodeFromString<DeletedInviteesResponse>(
            """{"deleted_user_uuids":["bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"]}""",
        )

        assertEquals(listOf("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"), response.deletedUserUuids)
    }

    /** Handed over to Bbbb as its creator, Dddd, deleted their account, who still signed it. */
    @Test
    fun `a group the server lists decodes with the caller's copy of its key`() {
        val groups = json.decodeFromString<List<GroupDto>>(
            """[{"uuid":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","owner_uuid":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            "creator_uuid":"dddddddd-dddd-4ddd-8ddd-dddddddddddd",
            "title":{"ciphertext":"dA==","nonce":"bg=="},"description":{"ciphertext":"ZA==","nonce":"bg=="},
            "currency":{"ciphertext":"Yw==","nonce":"bg=="},"revision":3,
            "signature":{"ciphertext":"cw==","nonce":"bg=="},
            "members":[{"user_uuid":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"},
            {"user_uuid":"cccccccc-cccc-4ccc-8ccc-cccccccccccc"}],
            "key_copy":{"ciphertext":"aw==","nonce":"bg=="},"key_copy_generation":2,"key_generation":3}]""",
        )

        val group = groups.single()

        assertEquals("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", group.ownerUuid)
        assertEquals("dddddddd-dddd-4ddd-8ddd-dddddddddddd", group.creatorUuid)
        assertEquals(EncryptedFieldDto(ciphertext = "Yw==", nonce = "bg=="), group.currency)
        assertEquals(3L, group.revision)
        assertEquals(EncryptedFieldDto(ciphertext = "cw==", nonce = "bg=="), group.signature)
        assertEquals(
            listOf("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            group.members.map { it.userUuid },
        )
        assertEquals(EncryptedFieldDto(ciphertext = "aw==", nonce = "bg=="), group.keyCopy)
        assertEquals(2L, group.keyCopyGeneration)
        assertEquals(3L, group.keyGeneration)
    }

    /** The server holds no copy of the caller's: one never put up, or dropped with a removal. */
    @Test
    fun `a group the server lists with no copy of its key decodes`() {
        val groups = json.decodeFromString<List<GroupDto>>(
            """[{"uuid":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","owner_uuid":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            "creator_uuid":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            "title":{"ciphertext":"dA==","nonce":"bg=="},"description":{"ciphertext":"ZA==","nonce":"bg=="},
            "currency":{"ciphertext":"Yw==","nonce":"bg=="},"revision":1,
            "signature":{"ciphertext":"cw==","nonce":"bg=="},
            "members":[{"user_uuid":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}],"key_copy":null,
            "key_copy_generation":null,"key_generation":1}]""",
        )

        assertNull(groups.single().keyCopy)
        assertNull(groups.single().keyCopyGeneration)
    }

    /** Nothing else is sent: the currency stays the one the group was created in, see [GroupEditor]. */
    @Test
    fun `an edit is written with the names the server reads`() {
        val written = Json.encodeToJsonElement(
            EditGroupBody.serializer(),
            EditGroupBody(
                revision = 2,
                title = EncryptedFieldDto(ciphertext = "dA==", nonce = "bg=="),
                description = EncryptedFieldDto(ciphertext = "ZA==", nonce = "bg=="),
                signature = EncryptedFieldDto(ciphertext = "cw==", nonce = "bg=="),
            ),
        ).jsonObject

        assertEquals(setOf("revision", "title", "description", "signature"), written.keys)
        assertEquals("2", written.getValue("revision").toString())
    }

    @Test
    fun `a key copy put up is written with the names the server reads`() {
        val written = Json.encodeToJsonElement(
            SaveGroupKeyCopyBody.serializer(),
            SaveGroupKeyCopyBody(keyCopy = EncryptedFieldDto(ciphertext = "aw==", nonce = "bg=="), keyGeneration = 4),
        ).jsonObject

        assertEquals(setOf("key_copy", "key_generation"), written.keys)
        assertEquals("4", written.getValue("key_generation").toString())
    }

    /** Ascending, from the second generation on: the first has no key before it. */
    @Test
    fun `the links back through a group's keys decode as the server lists them`() {
        val links = json.decodeFromString<List<GroupKeyLinkDto>>(
            """[{"generation":2,"previous_key":{"ciphertext":"aw==","nonce":"bg=="}},
            {"generation":3,"previous_key":{"ciphertext":"bA==","nonce":"bQ=="}}]""",
        )

        assertEquals(listOf(2L, 3L), links.map { it.generation })
        assertEquals(EncryptedFieldDto(ciphertext = "bA==", nonce = "bQ=="), links[1].previousKey)
    }
}
