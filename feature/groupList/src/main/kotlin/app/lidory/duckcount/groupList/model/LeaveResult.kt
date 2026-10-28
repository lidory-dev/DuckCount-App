package app.lidory.duckcount.groupList.model

/**
 * What came of the user leaving a group, see
 * [app.lidory.duckcount.groupList.callBacks.GroupSettingsCallBacks.leaveGroup].
 */
sealed interface LeaveResult {

    /**
     * The user is out of the group on this device, which keeps it read-only, whether or not the
     * server heard the leave yet: it is sent again until it does.
     */
    data object Left : LeaveResult

    /**
     * Nothing was written, for [failure], so the group is as it was and can be left again. Never
     * for want of a network, which only delays the server hearing a leave made already, see
     * [Left]: only for what the user cannot act on, see [GroupCreationFailure.UNKNOWN].
     */
    data class Failed(val failure: GroupCreationFailure) : LeaveResult
}
