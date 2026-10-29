package app.lidory.duckcount.groupList.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.groupList.R

/** What reading the history of a group's information gave. */
sealed interface GroupHistory {

    /**
     * Its versions, newest first and the earliest kept last, the one the group is at now marked as
     * such, see [GroupInformationVersion.isCurrent]: one at least.
     */
    data class Versions(val versions: List<GroupInformationVersion>) : GroupHistory

    /** Why there are none to show. */
    data class Unavailable(val error: GroupHistoryError) : GroupHistory
}

/**
 * One version of a group's title and description, as its history shows it: checked against its
 * creator's signature as the version the group shows is, as the composeApp callback impl reads it.
 */
data class GroupInformationVersion(
    /** Which version this is, which no other version of the group shares. */
    val revision: Long,

    val title: String,

    /** Empty when it has none. */
    val description: String,

    /** Whether this is the group as it was created, rather than as an edit left it. */
    val isCreation: Boolean,

    /**
     * Whether this is the version the group is at now: the newest its history holds, which the
     * group shows once this device has synced. None is when the server holds back the one the
     * group shows, as it would to have the group go back to one it replaced, which it never does.
     */
    val isCurrent: Boolean,

    /** When the server stored it, in milliseconds since the epoch. */
    val revisedAt: Long,
)

/**
 * Why the history of a group could not be read, at a granularity the screen can explain, as for an
 * edit, see [GroupEditError]. The composeApp callback impl maps the shared error onto this.
 *
 * Nothing is written by reading it, so it can be read again as it is: the screen offers to.
 */
sealed interface GroupHistoryError {

    /** For a reason told in a sentence of its own, as a creation's would be, see [GroupCreationFailure]. */
    data class Failure(val failure: GroupCreationFailure) : GroupHistoryError

    /**
     * The device locked before the history could be read, which this device needs to be unlocked
     * for: once it is, it can be read again.
     */
    data object DeviceLocked : GroupHistoryError

    /**
     * None of its versions could be read on this device: a change to the group's members since
     * handed its members a new key, which this device reads as it syncs, the history with it.
     */
    data object Unreadable : GroupHistoryError
}

/** The sentence [this] is told in, see [message]: a creation's for what kept it from being read. */
@StringRes
fun GroupHistoryError.messageRes(): Int = when (this) {
    is GroupHistoryError.Failure -> failure.messageRes()
    GroupHistoryError.DeviceLocked -> R.string.group_history_error_device_locked
    GroupHistoryError.Unreadable -> R.string.group_history_error_unreadable
}

/** What the screen says of [this]. */
@Composable
fun GroupHistoryError.message(): String = stringResource(messageRes())
