package database.domain

data class GroupWithMembers(
    val uuid: String,
    val description: String,
    val title: String,
    val currency: Currency,
    val groupMembers: List<GroupMember>,

    /** Whether the creator removed the user from the group, which stays, read-only. */
    val isLeft: Boolean,

    /**
     * Whether a change to the group is waiting on the server, its creation or a change to its
     * members, until which no other change to its members can be made.
     */
    val hasPendingChange: Boolean,

    /**
     * Whether the user may add and remove members from this device: they created the group, which
     * makes them the one member who does, and this device holds its MLS state, which a log-out
     * takes. Taken whether or not [hasPendingChange].
     */
    val canChangeMembers: Boolean,

    /**
     * Whether this device can tell who the group's members are on the group's word, which a
     * contact request to one of them is checked against, see `contact.ContactRequestSender`: the
     * user was not removed from it, this device holds its MLS state, which a log-out takes, and
     * its creator, whose word its tree is, is not blocked, see
     * [IdentityConflictEntity][database.data.local.entity.IdentityConflictEntity].
     */
    val canVouchForMembers: Boolean,
)
