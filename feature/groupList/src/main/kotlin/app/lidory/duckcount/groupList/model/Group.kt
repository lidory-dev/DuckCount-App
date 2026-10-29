package app.lidory.duckcount.groupList.model

data class Group(
    val uuid: String,
    val description: String,
    val title: String,
    val currency: Currency,

    /**
     * Whether the user is no longer a member of the group, removed by its creator or left, which
     * stays, read-only.
     */
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

    /**
     * Whether the user may edit the group's title and description from its settings: they created
     * it, the one member whose versions of it the others show, and which they are never removed
     * from. Whether or not this device holds what changing its members takes, and whether or not
     * [hasPendingChange], until which an edit is refused.
     */
    val canEdit: Boolean,

    /**
     * Whether this device can tell who the members are on the group's word, which a contact
     * request to one of them is checked against: the user is still in it, this device holds what
     * the group says of them, which a log-out does not bring back, and its creator, whose word it
     * is, is not blocked over a changed identity.
     */
    val canVouchForMembers: Boolean,

    /**
     * Whether the user may leave the group: they are still in it, and did not create it, its
     * creator never leaving, as nobody else could change its members.
     */
    val canLeave: Boolean,
)
