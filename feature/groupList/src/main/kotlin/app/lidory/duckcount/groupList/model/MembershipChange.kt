package app.lidory.duckcount.groupList.model

import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.android.ui.text.listedNames
import app.lidory.duckcount.groupList.R

/**
 * What came of adding members to a group, or removing one, see
 * [app.lidory.duckcount.groupList.callBacks.GroupSettingsCallBacks.addMembers].
 */
sealed interface MembershipChangeResult {

    /** The server took the change, or there was none to make: the group lists its members as they now are. */
    data object Made : MembershipChangeResult

    /**
     * The server has not been heard to take the change yet: it is sent again until it is, and
     * reaches the members then, the group showing a change pending meanwhile, see
     * [GroupWithMembers.hasPendingChange].
     */
    data object Pending : MembershipChangeResult

    /** The change did not go through, for [error]: nothing is left of it, so it can be made again. */
    data class Failed(val error: MembershipChangeError) : MembershipChangeResult
}

/**
 * Why adding members to a group, or removing one, did not go through, at a granularity the
 * screen can explain, as for a creation, see [GroupCreationError]. The composeApp callback impl
 * maps the shared error onto this.
 */
sealed interface MembershipChangeError {

    /** For a reason told in a sentence of its own, as a creation's would be, see [GroupCreationFailure]. */
    data class Failure(val failure: GroupCreationFailure) : MembershipChangeError

    /**
     * Members picked could not be added, for [refusal], by their names, [fullNames], in the order
     * they were picked: every one of them it applies to, and nobody was added, so that the user
     * can deal with all of them before trying again.
     */
    data class Members(val refusal: MemberRefusal, val fullNames: List<String>) : MembershipChangeError
}

/**
 * Told with the names of the members it applies to, see [MembershipChangeError.Members], in the
 * words of an addition rather than of a creation, see [MemberRefusal.messageRes].
 */
@PluralsRes
fun MemberRefusal.additionMessageRes(): Int = when (this) {
    MemberRefusal.NOT_CONTACTS -> R.plurals.group_members_error_not_contacts
    MemberRefusal.IDENTITY_CHANGED -> R.plurals.group_members_error_identity_changed
    MemberRefusal.UNREACHABLE -> R.plurals.group_members_error_unreachable
    MemberRefusal.DELETED -> R.plurals.group_members_error_deleted
}

/** What the screen says of [this]. */
@Composable
fun MembershipChangeError.message(): String = when (this) {
    is MembershipChangeError.Failure -> stringResource(failure.messageRes())

    is MembershipChangeError.Members -> pluralStringResource(
        refusal.additionMessageRes(),
        fullNames.size,
        listedNames(names = fullNames),
    )
}

/**
 * What the screen says of [this] once answered, if anything: nothing of a change made, which the
 * members listed show.
 */
@Composable
fun MembershipChangeResult.message(): String? = when (this) {
    MembershipChangeResult.Made -> null
    MembershipChangeResult.Pending -> stringResource(R.string.group_members_change_pending)
    is MembershipChangeResult.Failed -> error.message()
}
