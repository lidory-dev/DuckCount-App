package group

import database.data.remote.EncryptedFieldDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

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
        welcomeMessage = "d2VsY29tZQ==",
        invitedMemberUuids = listOf("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
    )

    @Test
    fun `a creation is written with the names the server reads`() {
        val written = Json.parseToJsonElement(pendingCreationOf(groupUuid = GROUP, body = body).body).jsonObject

        assertEquals(
            setOf("nonce", "title", "description", "currency", "welcome_message", "invited_member_uuids"),
            written.keys,
        )
    }

    /** Sent again exactly as it first went out, see `PendingGroupCreationEntity`. */
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

    @Test
    fun `a group the server lists decodes`() {
        val groups = json.decodeFromString<List<GroupDto>>(
            """[{"uuid":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","owner_uuid":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            "title":{"ciphertext":"dA==","nonce":"bg=="},"description":{"ciphertext":"ZA==","nonce":"bg=="},
            "currency":{"ciphertext":"Yw==","nonce":"bg=="},
            "members":[{"user_uuid":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"},
            {"user_uuid":"cccccccc-cccc-4ccc-8ccc-cccccccccccc"}]}]""",
        )

        val group = groups.single()

        assertEquals("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", group.ownerUuid)
        assertEquals(EncryptedFieldDto(ciphertext = "Yw==", nonce = "bg=="), group.currency)
        assertEquals(
            listOf("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            group.members.map { it.userUuid },
        )
    }
}
