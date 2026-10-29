package database.domain

data class GroupWithMembers(
    val uuid: String,
    val description: String,
    val title: String,
    val currency: Currency,
    val groupMembers: List<GroupMember>,

    /**
     * Whether the user is no longer a member of the group, removed by its creator or left, which
     * stays, read-only.
     */
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
     * Whether the user may edit the group's title and description, see `group.GroupEditor`: they
     * created it, which makes them the one member whose versions of it the others show, and which
     * they are never removed from. Whether or not this device holds its MLS state, each version
     * being signed with the user's identity, which outlives a log-out, and whether or not
     * [hasPendingChange], until which an edit is refused.
     */
    val canEdit: Boolean,

    /**
     * Whether this device can tell who the group's members are on the group's word, which a
     * contact request to one of them is checked against, see `contact.ContactRequestSender`: the
     * user is still in it, this device holds its MLS state, which a log-out takes, and its
     * creator, whose word its tree is, is not blocked, see
     * [IdentityConflictEntity][database.data.local.entity.IdentityConflictEntity].
     */
    val canVouchForMembers: Boolean,

    /**
     * Whether the user may leave the group, see `group.GroupLeaver`: they are still in it, and did
     * not create it, its creator never leaving, as nobody else could change its members.
     */
    val canLeave: Boolean,
)
