package group

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"

/** How the server's rejoin routes and the listing's flag for them are read and written, see [GroupRejoiner]. */
class GroupRejoinDtoTest {

    /** As the client decodes every body. */
    private val json = Json { ignoreUnknownKeys = true }

    /** A group as a server that predates rejoins lists it, without [GroupDto.groupInfoWanted]. */
    private val listed = """{"uuid":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","owner_uuid":"$ALICE",
        "title":{"ciphertext":"dA==","nonce":"bg=="},"description":{"ciphertext":"ZA==","nonce":"bg=="},
        "currency":{"ciphertext":"Yw==","nonce":"bg=="},"revision":1,
        "signature":{"ciphertext":"cw==","nonce":"bg=="},"information_key_generation":1,
        "members":[{"user_uuid":"$ALICE"},{"user_uuid":"$BOB"}],"departed":[],
        "key_copy":null,"key_copy_generation":null,"key_generation":4"""

    @Test
    fun `a group listed by a server that predates rejoins wants no GroupInfo`() {
        assertFalse(json.decodeFromString<GroupDto>("$listed}").groupInfoWanted)
    }

    @Test
    fun `a group listed as wanting a GroupInfo says so`() {
        assertTrue(json.decodeFromString<GroupDto>("""$listed,"group_info_wanted":true}""").groupInfoWanted)
    }

    @Test
    fun `the publishers of GroupInfos are read in the order listed`() {
        val publishers = json.decodeFromString<List<GroupInfoPublisherDto>>(
            """[{"publisher_uuid":"$BOB","epoch":4},{"publisher_uuid":"$ALICE","epoch":4}]""",
        )

        assertEquals(
            listOf(GroupInfoPublisherDto(publisherUuid = BOB, epoch = 4), GroupInfoPublisherDto(publisherUuid = ALICE, epoch = 4)),
            publishers,
        )
    }

    @Test
    fun `a GroupInfo published is read with who published it and its epoch`() {
        val published = json.decodeFromString<GroupInfoDto>(
            """{"publisher_uuid":"$BOB","epoch":4,"group_info":"Z3JvdXAgaW5mbw=="}""",
        )

        assertEquals(GroupInfoDto(publisherUuid = BOB, epoch = 4, groupInfo = "Z3JvdXAgaW5mbw=="), published)
    }

    @Test
    fun `a GroupInfo is published with the name the server reads`() {
        assertEquals(
            setOf("group_info"),
            json.encodeToJsonElement(PublishGroupInfoBody.serializer(), PublishGroupInfoBody(groupInfo = "Z3JvdXAgaW5mbw=="))
                .jsonObject.keys,
        )
    }
}
