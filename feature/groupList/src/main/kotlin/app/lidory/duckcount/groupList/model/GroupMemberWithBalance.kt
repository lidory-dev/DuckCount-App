package app.lidory.duckcount.groupList.model

data class GroupMemberWithBalance(
    val member: GroupMemberWithFullName,
    val balance: String = "",

    /**
     * Whether the creator removed them from the group: the expenses naming them stay, and so does
     * their balance.
     */
    val isFormerMember: Boolean = false,
)