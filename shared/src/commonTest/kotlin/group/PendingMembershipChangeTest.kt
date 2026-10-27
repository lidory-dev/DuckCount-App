package group

import database.data.local.entity.MembershipChangeKind
import database.data.local.entity.PendingMembershipChangeEntity
import database.data.remote.EncryptedFieldDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val DAVE = "44444444-4444-4444-4444-444444444444"

/** How a change to a group's members is written for the server, and kept until it is taken. */
class PendingMembershipChangeTest {

    private val addition = AddGroupMembersBody(
        nonce = "AAECAwQFBgcICQoLDA0ODw==",
        commitMessage = "Y29tbWl0",
        welcomeMessage = "d2VsY29tZQ==",
        newMemberUuids = listOf(DAVE),
    )

    private val removal = RemoveGroupMembersBody(
        nonce = "AAECAwQFBgcICQoLDA0ODw==",
        commitMessage = "Y29tbWl0",
        removedMemberUuids = listOf(DAVE),
    )

    private val rotation = RotateGroupKeyBody(
        nonce = "AAECAwQFBgcICQoLDA0ODw==",
        keyMessage = "a2V5",
        title = EncryptedFieldDto(ciphertext = "dGl0bGU=", nonce = "bm9uY2Ux"),
        description = EncryptedFieldDto(ciphertext = "ZGVzY3JpcHRpb24=", nonce = "bm9uY2Uy"),
        currency = EncryptedFieldDto(ciphertext = "Y3VycmVuY3k=", nonce = "bm9uY2Uz"),
    )

    @Test
    fun `each change is written with the names the server reads`() {
        assertEquals(
            setOf("nonce", "commit_message", "welcome_message", "new_member_uuids"),
            keysOf(PendingMembershipChange.Addition(groupUuid = GROUP, body = addition, commitEpoch = 1).toEntity()),
        )
        assertEquals(
            setOf("nonce", "commit_message", "removed_member_uuids"),
            keysOf(PendingMembershipChange.Removal(groupUuid = GROUP, body = removal, commitEpoch = 1).toEntity()),
        )
        assertEquals(
            setOf("nonce", "key_message", "title", "description", "currency"),
            keysOf(
                PendingMembershipChange.KeyRotation(groupUuid = GROUP, body = rotation, groupKey = ByteArray(32))
                    .toEntity(),
            ),
        )
    }

    /** Sent again exactly as it first went out, see `PendingMembershipChangeEntity`. */
    @Test
    fun `an addition kept pending is sent again as it was`() {
        val kept = PendingMembershipChange.Addition(groupUuid = GROUP, body = addition, commitEpoch = 7).toEntity()

        assertEquals(MembershipChangeKind.ADDITION, kept.kind)
        assertNull(kept.groupKey)

        val change = assertIs<PendingMembershipChange.Addition>(kept.toChange())

        assertEquals(GROUP, change.groupUuid)
        assertEquals(addition, change.body)
        assertEquals(7, change.commitEpoch)
    }

    @Test
    fun `a removal kept pending is sent again as it was`() {
        val kept = PendingMembershipChange.Removal(groupUuid = GROUP, body = removal, commitEpoch = 7).toEntity()

        assertEquals(MembershipChangeKind.REMOVAL, kept.kind)

        val change = assertIs<PendingMembershipChange.Removal>(kept.toChange())

        assertEquals(removal, change.body)
        assertEquals(7, change.commitEpoch)
    }

    @Test
    fun `a key rotation kept pending is sent again with the key it hands over`() {
        val groupKey = ByteArray(32) { it.toByte() }
        val kept = PendingMembershipChange.KeyRotation(groupUuid = GROUP, body = rotation, groupKey = groupKey)
            .toEntity()

        assertEquals(MembershipChangeKind.KEY_ROTATION, kept.kind)
        assertNull(kept.commitEpoch)

        val change = assertIs<PendingMembershipChange.KeyRotation>(kept.toChange())

        assertEquals(rotation, change.body)
        assertContentEquals(groupKey, change.groupKey)
    }

    /** What the server names the change by, with the creator's UUID. */
    @Test
    fun `every change gets a nonce of 16 bytes of its own`() {
        val nonce = newChangeNonce()

        assertEquals(16, Base64.Default.decode(nonce).size)
        assertNotEquals(nonce, newChangeNonce())
    }

    /** The names the body of [change] is written with. */
    private fun keysOf(change: PendingMembershipChangeEntity) = Json.parseToJsonElement(change.body).jsonObject.keys
}
