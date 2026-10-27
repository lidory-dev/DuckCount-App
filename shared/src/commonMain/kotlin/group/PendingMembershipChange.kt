package group

import database.data.local.entity.MembershipChangeKind
import database.data.local.entity.PendingMembershipChangeEntity
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A change to the members of a group the user created, kept until the server takes it and this
 * device acted on that, see [PendingMembershipChangeEntity], read for what it is.
 */
internal sealed interface PendingMembershipChange {
    val groupUuid: String

    /** Members added, by the Commit [body] carries, built in [commitEpoch]. */
    class Addition(
        override val groupUuid: String,
        val body: AddGroupMembersBody,
        val commitEpoch: Long,
    ) : PendingMembershipChange

    /** Members removed, by the Commit [body] carries, built in [commitEpoch]. */
    class Removal(
        override val groupUuid: String,
        val body: RemoveGroupMembersBody,
        val commitEpoch: Long,
    ) : PendingMembershipChange

    /** The key rotation after a removal, handing the members who remain [groupKey]. */
    class KeyRotation(
        override val groupUuid: String,
        val body: RotateGroupKeyBody,
        val groupKey: ByteArray,
    ) : PendingMembershipChange
}

/** This change as it is kept, see [toChange]. */
internal fun PendingMembershipChange.toEntity(): PendingMembershipChangeEntity =
    when (this) {
        is PendingMembershipChange.Addition -> PendingMembershipChangeEntity(
            groupUuid = groupUuid,
            kind = MembershipChangeKind.ADDITION,
            body = Json.encodeToString(AddGroupMembersBody.serializer(), body),
            commitEpoch = commitEpoch,
            groupKey = null,
        )

        is PendingMembershipChange.Removal -> PendingMembershipChangeEntity(
            groupUuid = groupUuid,
            kind = MembershipChangeKind.REMOVAL,
            body = Json.encodeToString(RemoveGroupMembersBody.serializer(), body),
            commitEpoch = commitEpoch,
            groupKey = null,
        )

        is PendingMembershipChange.KeyRotation -> PendingMembershipChangeEntity(
            groupUuid = groupUuid,
            kind = MembershipChangeKind.KEY_ROTATION,
            body = Json.encodeToString(RotateGroupKeyBody.serializer(), body),
            commitEpoch = null,
            groupKey = groupKey,
        )
    }

/** The change [toEntity] kept. */
internal fun PendingMembershipChangeEntity.toChange(): PendingMembershipChange =
    when (kind) {
        MembershipChangeKind.ADDITION -> PendingMembershipChange.Addition(
            groupUuid = groupUuid,
            body = Json.decodeFromString(AddGroupMembersBody.serializer(), body),
            commitEpoch = checkNotNull(commitEpoch) { "An addition is kept with the epoch of its Commit." },
        )

        MembershipChangeKind.REMOVAL -> PendingMembershipChange.Removal(
            groupUuid = groupUuid,
            body = Json.decodeFromString(RemoveGroupMembersBody.serializer(), body),
            commitEpoch = checkNotNull(commitEpoch) { "A removal is kept with the epoch of its Commit." },
        )

        MembershipChangeKind.KEY_ROTATION -> PendingMembershipChange.KeyRotation(
            groupUuid = groupUuid,
            body = Json.decodeFromString(RotateGroupKeyBody.serializer(), body),
            groupKey = checkNotNull(groupKey) { "A key rotation is kept with the key it hands over." },
        )
    }

/**
 * The nonce naming a new change to a group's members, Base64 encoded: 16 bytes of a random UUID,
 * drawn from the platform's secure generator. The server derives the change's UUID from it and the
 * creator's, which only a resend of this very change repeats.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun newChangeNonce(): String = Base64.Default.encode(Uuid.random().toByteArray())
