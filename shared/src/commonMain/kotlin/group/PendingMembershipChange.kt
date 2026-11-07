package group

import database.data.local.entity.MembershipChangeKind
import database.data.local.entity.PendingMembershipChangeEntity
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A Commit of the user's to a group, kept until the server takes it and this device acted on that,
 * see [PendingMembershipChangeEntity], read for what it is. Each was built in [commitEpoch].
 */
internal sealed interface PendingMembershipChange {
    val groupUuid: String

    val commitEpoch: Long

    /** Members added by the creator, by the Commit [body] carries. */
    class Addition(
        override val groupUuid: String,
        val body: AddGroupMembersBody,
        override val commitEpoch: Long,
    ) : PendingMembershipChange

    /** Members removed by the creator, by the Commit [body] carries. */
    class Removal(
        override val groupUuid: String,
        val body: RemoveGroupMembersBody,
        override val commitEpoch: Long,
    ) : PendingMembershipChange

    /**
     * Departed members removed by any member, the creator included, through the server's route for
     * them, by the Commit [body] carries, see [GroupMembershipChanger.removeDepartedUnderGroupsLock].
     */
    class DepartedRemoval(
        override val groupUuid: String,
        val body: RemoveDepartedMembersBody,
        override val commitEpoch: Long,
    ) : PendingMembershipChange

    /** The creator's Commit in place of one nobody could apply, which [body] carries. */
    class Replacement(
        override val groupUuid: String,
        val body: ReplaceGroupCommitBody,
        override val commitEpoch: Long,
    ) : PendingMembershipChange

    /**
     * Any member's rejoin of a group a log-out took the MLS state of, by the Commit from outside it
     * [body] carries, see [GroupRejoiner]: built from a GroupInfo of [commitEpoch], and applied
     * already, the group on this device in the epoch after it from the start.
     */
    class Rejoin(
        override val groupUuid: String,
        val body: RejoinGroupBody,
        override val commitEpoch: Long,
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
        )

        is PendingMembershipChange.Removal -> PendingMembershipChangeEntity(
            groupUuid = groupUuid,
            kind = MembershipChangeKind.REMOVAL,
            body = Json.encodeToString(RemoveGroupMembersBody.serializer(), body),
            commitEpoch = commitEpoch,
        )

        is PendingMembershipChange.DepartedRemoval -> PendingMembershipChangeEntity(
            groupUuid = groupUuid,
            kind = MembershipChangeKind.DEPARTED_REMOVAL,
            body = Json.encodeToString(RemoveDepartedMembersBody.serializer(), body),
            commitEpoch = commitEpoch,
        )

        is PendingMembershipChange.Replacement -> PendingMembershipChangeEntity(
            groupUuid = groupUuid,
            kind = MembershipChangeKind.REPLACEMENT,
            body = Json.encodeToString(ReplaceGroupCommitBody.serializer(), body),
            commitEpoch = commitEpoch,
        )

        is PendingMembershipChange.Rejoin -> PendingMembershipChangeEntity(
            groupUuid = groupUuid,
            kind = MembershipChangeKind.REJOIN,
            body = Json.encodeToString(RejoinGroupBody.serializer(), body),
            commitEpoch = commitEpoch,
        )
    }

/** The change [toEntity] kept. */
internal fun PendingMembershipChangeEntity.toChange(): PendingMembershipChange =
    when (kind) {
        MembershipChangeKind.ADDITION -> PendingMembershipChange.Addition(
            groupUuid = groupUuid,
            body = Json.decodeFromString(AddGroupMembersBody.serializer(), body),
            commitEpoch = commitEpoch,
        )

        MembershipChangeKind.REMOVAL -> PendingMembershipChange.Removal(
            groupUuid = groupUuid,
            body = Json.decodeFromString(RemoveGroupMembersBody.serializer(), body),
            commitEpoch = commitEpoch,
        )

        MembershipChangeKind.DEPARTED_REMOVAL -> PendingMembershipChange.DepartedRemoval(
            groupUuid = groupUuid,
            body = Json.decodeFromString(RemoveDepartedMembersBody.serializer(), body),
            commitEpoch = commitEpoch,
        )

        MembershipChangeKind.REPLACEMENT -> PendingMembershipChange.Replacement(
            groupUuid = groupUuid,
            body = Json.decodeFromString(ReplaceGroupCommitBody.serializer(), body),
            commitEpoch = commitEpoch,
        )

        MembershipChangeKind.REJOIN -> PendingMembershipChange.Rejoin(
            groupUuid = groupUuid,
            body = Json.decodeFromString(RejoinGroupBody.serializer(), body),
            commitEpoch = commitEpoch,
        )
    }

/**
 * The nonce naming a new change to a group's members, Base64 encoded: 16 bytes of a random UUID,
 * drawn from the platform's secure generator. The server derives the change's UUID from it and the
 * committer's, which only a resend of this very change repeats.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun newChangeNonce(): String = Base64.Default.encode(Uuid.random().toByteArray())
