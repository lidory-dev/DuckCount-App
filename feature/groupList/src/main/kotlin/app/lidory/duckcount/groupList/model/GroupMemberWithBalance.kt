package app.lidory.duckcount.groupList.model

data class GroupMemberWithBalance(
    val member: GroupMemberWithFullName,
    val balance: String = "",

    /**
     * Whether they are no longer in the group, removed by its creator, or by any member's device
     * once they left: the expenses naming them stay, and so does their balance.
     */
    val isFormerMember: Boolean = false,
)