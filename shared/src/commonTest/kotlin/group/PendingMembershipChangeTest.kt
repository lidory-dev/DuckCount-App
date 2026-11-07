package group

import database.data.local.entity.MembershipChangeKind
import database.data.local.entity.PendingMembershipChangeEntity
import database.data.remote.EncryptedFieldDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val DAVE = "44444444-4444-4444-4444-444444444444"

/** How a Commit of the user's is written for the server, and kept until it is taken. */
class PendingMembershipChangeTest {

    /** As the client writes every body it sends. */
    private val clientJson = Json { ignoreUnknownKeys = true }

    private val previousKey = EncryptedFieldDto(ciphertext = "cHJldmlvdXM=", nonce = "bm9uY2U5")

    private val keyCopy = EncryptedFieldDto(ciphertext = "a2V5", nonce = "bm9uY2U0")

    private val addition = AddGroupMembersBody(
        nonce = "AAECAwQFBgcICQoLDA0ODw==",
        commitMessage = "Y29tbWl0",
        profileKeysMessage = "cHJvZmlsZSBrZXlz",
        welcomeMessage = "d2VsY29tZQ==",
        newMemberUuids = listOf(DAVE),
        previousKey = previousKey,
        keyCopy = keyCopy,
    )

    private val removal = RemoveGroupMembersBody(
        nonce = "AAECAwQFBgcICQoLDA0ODw==",
        commitMessage = "Y29tbWl0",
        removedMemberUuids = listOf(DAVE),
        previousKey = previousKey,
        keyCopy = keyCopy,
    )

    private val departedRemoval = RemoveDepartedMembersBody(
        nonce = "AAECAwQFBgcICQoLDA0ODw==",
        commitMessage = "Y29tbWl0",
        removedMemberUuids = listOf(DAVE),
        previousKey = previousKey,
        keyCopy = keyCopy,
    )

    private val replacement = ReplaceGroupCommitBody(
        nonce = "AAECAwQFBgcICQoLDA0ODw==",
        replacesEpoch = 4,
        commitMessage = "Y29tbWl0",
        removedMemberUuids = listOf(DAVE),
        previousKey = previousKey,
        keyCopy = keyCopy,
    )

    private val rejoin = RejoinGroupBody(
        nonce = "AAECAwQFBgcICQoLDA0ODw==",
        commitMessage = "Y29tbWl0",
        keyCopy = keyCopy,
        previousKey = previousKey,
    )

    @Test
    fun `each change is written with the names the server reads`() {
        assertEquals(
            setOf(
                "nonce",
                "commit_message",
                "profile_keys_message",
                "welcome_message",
                "new_member_uuids",
                "previous_key",
                "key_copy",
            ),
            keysOf(PendingMembershipChange.Addition(groupUuid = GROUP, body = addition, commitEpoch = 1).toEntity()),
        )
        assertEquals(
            setOf("nonce", "commit_message", "removed_member_uuids", "previous_key", "key_copy"),
            keysOf(PendingMembershipChange.Removal(groupUuid = GROUP, body = removal, commitEpoch = 1).toEntity()),
        )
        assertEquals(
            setOf("nonce", "commit_message", "removed_member_uuids", "previous_key", "key_copy"),
            keysOf(
                PendingMembershipChange.DepartedRemoval(groupUuid = GROUP, body = departedRemoval, commitEpoch = 1)
                    .toEntity(),
            ),
        )
        assertEquals(
            setOf("nonce", "replaces_epoch", "commit_message", "removed_member_uuids", "previous_key", "key_copy"),
            keysOf(
                PendingMembershipChange.Replacement(groupUuid = GROUP, body = replacement, commitEpoch = 4).toEntity(),
            ),
        )
    }

    /** Sent again exactly as it first went out, see `PendingMembershipChangeEntity`. */
    @Test
    fun `an addition kept pending is sent again as it was`() {
        val kept = PendingMembershipChange.Addition(groupUuid = GROUP, body = addition, commitEpoch = 7).toEntity()

        assertEquals(MembershipChangeKind.ADDITION, kept.kind)

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
    fun `a removal of those who departed kept pending is sent again as it was`() {
        val kept = PendingMembershipChange.DepartedRemoval(groupUuid = GROUP, body = departedRemoval, commitEpoch = 7)
            .toEntity()

        assertEquals(MembershipChangeKind.DEPARTED_REMOVAL, kept.kind)

        val change = assertIs<PendingMembershipChange.DepartedRemoval>(kept.toChange())

        assertEquals(departedRemoval, change.body)
        assertEquals(7, change.commitEpoch)
    }

    @Test
    fun `a replacement kept pending is sent again as it was`() {
        val kept = PendingMembershipChange.Replacement(groupUuid = GROUP, body = replacement, commitEpoch = 4)
            .toEntity()

        assertEquals(MembershipChangeKind.REPLACEMENT, kept.kind)

        val change = assertIs<PendingMembershipChange.Replacement>(kept.toChange())

        assertEquals(replacement, change.body)
        assertEquals(4, change.commitEpoch)
    }

    @Test
    fun `a rejoin is written with the names the server reads`() {
        assertEquals(
            setOf("nonce", "commit_message", "key_copy", "previous_key"),
            keysOf(PendingMembershipChange.Rejoin(groupUuid = GROUP, body = rejoin, commitEpoch = 3).toEntity()),
        )
    }

    /** No link to make, the user's copy being of an earlier epoch: none is sent, not even as null. */
    @Test
    fun `a rejoin with no link back leaves it out`() {
        val unlinked = rejoin.copy(previousKey = null)

        assertEquals(
            setOf("nonce", "commit_message", "key_copy"),
            keysOf(PendingMembershipChange.Rejoin(groupUuid = GROUP, body = unlinked, commitEpoch = 3).toEntity()),
        )
        assertEquals(
            setOf("nonce", "commit_message", "key_copy"),
            clientJson.encodeToJsonElement(RejoinGroupBody.serializer(), unlinked).jsonObject.keys,
        )
    }

    @Test
    fun `a rejoin kept pending is sent again as it was`() {
        val kept = PendingMembershipChange.Rejoin(groupUuid = GROUP, body = rejoin, commitEpoch = 3).toEntity()

        assertEquals(MembershipChangeKind.REJOIN, kept.kind)

        val change = assertIs<PendingMembershipChange.Rejoin>(kept.toChange())

        assertEquals(rejoin, change.body)
        assertEquals(3, change.commitEpoch)

        val unlinked = PendingMembershipChange.Rejoin(groupUuid = GROUP, body = rejoin.copy(previousKey = null), commitEpoch = 3)
            .toEntity()

        assertNull(assertIs<PendingMembershipChange.Rejoin>(unlinked.toChange()).body.previousKey)
    }

    /** What the server names the change by, with the committer's UUID. */
    @Test
    fun `every change gets a nonce of 16 bytes of its own`() {
        val nonce = newChangeNonce()

        assertEquals(16, Base64.Default.decode(nonce).size)
        assertNotEquals(nonce, newChangeNonce())
    }

    /** The names the body of [change] is written with. */
    private fun keysOf(change: PendingMembershipChangeEntity) = Json.parseToJsonElement(change.body).jsonObject.keys
}
