package app.lidory.duckcount.groupList.model

data class Group(
    val uuid: String,
    val description: String,
    val title: String,
    val currency: Currency,

    /** Whether the creator removed the user from the group, which stays, read-only. */
    val isLeft: Boolean,
)

data class GroupWithMembers(
    val uuid: String,
    val description: String,
    val title: String,
    val currency: Currency,
    val members: List<GroupMember>,

    /** See [Group.isLeft]. */
    val isLeft: Boolean,

    /**
     * Whether a change to the group is waiting on the server, its creation or a change to its
     * members, until which no other change to its members can be made.
     */
    val hasPendingChange: Boolean,

    /**
     * Whether the user may add and remove members from this device: they created the group, which
     * its creator is never removed from, and this device holds what changing it takes, which a
     * log-out does not bring back. Whether or not [hasPendingChange].
     */
    val canChangeMembers: Boolean,
)
