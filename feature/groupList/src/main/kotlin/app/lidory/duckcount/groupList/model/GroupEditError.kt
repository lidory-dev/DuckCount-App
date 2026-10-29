package app.lidory.duckcount.groupList.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.groupList.R

/**
 * Why what the user edited of a group was not saved, at a granularity the form can explain, as for
 * a creation, see [GroupCreationError]. The composeApp callback impl maps the shared error onto
 * this.
 *
 * Nothing is left of an edit that was not saved: the form shows what the user typed, for them to
 * save again, and the group what it showed, but after [Outdated], when it shows the newer version
 * it was read again for.
 */
sealed interface GroupEditError {

    /** For a reason told in a sentence of its own, as a creation's would be, see [GroupCreationFailure]. */
    data class Failure(val failure: GroupCreationFailure) : GroupEditError

    /**
     * A change to the group is still on its way to the server, its creation or a change to its
     * members, until which it cannot be edited.
     */
    data object ChangePending : GroupEditError

    /**
     * The group was edited since this device last read it, an earlier edit of the user's whose
     * answer was lost, to something other than what the form holds: it is read again, and the form
     * can be saved over it.
     */
    data object Outdated : GroupEditError
}

/** The sentence [this] is told in, see [message]: a creation's for what kept it from going through. */
@StringRes
fun GroupEditError.messageRes(): Int = when (this) {
    is GroupEditError.Failure -> failure.messageRes()
    GroupEditError.ChangePending -> R.string.group_edit_error_change_pending
    GroupEditError.Outdated -> R.string.group_edit_error_outdated
}

/** What the form says of [this]. */
@Composable
fun GroupEditError.message(): String = stringResource(messageRes())
