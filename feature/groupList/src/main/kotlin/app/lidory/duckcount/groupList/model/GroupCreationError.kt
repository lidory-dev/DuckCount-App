package app.lidory.duckcount.groupList.model

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.android.ui.text.listedNames
import app.lidory.duckcount.groupList.R

/**
 * Why creating the group did not go through, at a granularity the form can explain.
 * The composeApp callback impl maps the shared error onto this.
 *
 * Nothing is left of a group that could not be created, so the user can try again as it is, or
 * once they changed what the message points at.
 */
sealed interface GroupCreationError {

    /** For a reason told in a sentence of its own, see [GroupCreationFailure]. */
    data class Failure(val failure: GroupCreationFailure) : GroupCreationError

    /**
     * Members the form lists could not be put in the group, for [refusal], by their names,
     * [fullNames], in the order the form lists them: every one of them it applies to, for the user
     * to deal with all of them at once rather than one attempt at a time.
     */
    data class Members(val refusal: MemberRefusal, val fullNames: List<String>) : GroupCreationError
}

/** What kept a creation from going through that names nobody. */
enum class GroupCreationFailure {
    /** No connectivity. */
    NO_INTERNET,

    /** The server took too long to answer. */
    TIMEOUT,

    /** The server is unreachable. */
    SERVER_UNREACHABLE,

    /** Anything else: nothing the user can act on, and nothing worth explaining in detail. */
    UNKNOWN,
}

/** Why the members [GroupCreationError.Members] names could not be put in the group. */
enum class MemberRefusal {
    /**
     * They are no longer the user's contacts, or ones this device cannot tell who they are yet,
     * never having reached them, see `group.GroupCreationError.NotContacts`.
     */
    NOT_CONTACTS,

    /** The safety number with them changed, see the contact's screen. */
    IDENTITY_CHANGED,

    /** They have nothing to be invited with for now, see `group.GroupCreationError.Unreachable`. */
    UNREACHABLE,

    /** They deleted their account. */
    DELETED,
}

@StringRes
fun GroupCreationFailure.messageRes(): Int = when (this) {
    GroupCreationFailure.NO_INTERNET -> R.string.group_creation_error_no_internet
    GroupCreationFailure.TIMEOUT -> R.string.group_creation_error_timeout
    GroupCreationFailure.SERVER_UNREACHABLE -> R.string.group_creation_error_server_unreachable
    GroupCreationFailure.UNKNOWN -> R.string.group_creation_error_unknown
}

/** Told with the names of the members it applies to, see [GroupCreationError.Members]. */
@PluralsRes
fun MemberRefusal.messageRes(): Int = when (this) {
    MemberRefusal.NOT_CONTACTS -> R.plurals.group_creation_error_not_contacts
    MemberRefusal.IDENTITY_CHANGED -> R.plurals.group_creation_error_identity_changed
    MemberRefusal.UNREACHABLE -> R.plurals.group_creation_error_unreachable
    MemberRefusal.DELETED -> R.plurals.group_creation_error_deleted
}

/** What the form says of [this]. */
@Composable
fun GroupCreationError.message(): String = when (this) {
    is GroupCreationError.Failure -> stringResource(failure.messageRes())

    is GroupCreationError.Members -> pluralStringResource(
        refusal.messageRes(),
        fullNames.size,
        listedNames(names = fullNames),
    )
}
