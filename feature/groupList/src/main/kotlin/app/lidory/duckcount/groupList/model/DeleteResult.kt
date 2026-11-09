package app.lidory.duckcount.groupList.model

/**
 * What came of the user deleting a group, see
 * [app.lidory.duckcount.groupList.callBacks.GroupSettingsCallBacks.deleteGroup].
 */
sealed interface DeleteResult {

    /** The group is gone from this device: for everybody too when the server took its deletion. */
    data object Deleted : DeleteResult

    /** The server did not take it, or was not heard to, for [failure], so it can be asked for again. */
    data class Failed(val failure: GroupCreationFailure) : DeleteResult
}
