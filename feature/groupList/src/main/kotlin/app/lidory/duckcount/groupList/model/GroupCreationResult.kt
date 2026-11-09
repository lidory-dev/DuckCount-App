package app.lidory.duckcount.groupList.model

/**
 * What came of the user creating a group, see
 * [app.lidory.duckcount.groupList.callBacks.GroupListCallBacks.createGroup].
 */
sealed interface GroupCreationResult {

    /** The group is kept on this device, as [groupUuid], which is where the user goes next. */
    data class Created(val groupUuid: String) : GroupCreationResult

    /** Nothing is left of it, for [error], see [GroupCreationError]. */
    data class Failed(val error: GroupCreationError) : GroupCreationResult
}
