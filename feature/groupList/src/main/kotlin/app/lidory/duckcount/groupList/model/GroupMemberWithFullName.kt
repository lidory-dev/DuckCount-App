package app.lidory.duckcount.groupList.model


data class GroupMemberWithFullName(
    val member: GroupMember,

    /**
     * Null for a member this device has no name for: one who is not the user's contact, whom the
     * user was put in a group with by somebody else. Shown with a neutral label rather than their
     * UUID.
     */
    val fullName: String?,
)
