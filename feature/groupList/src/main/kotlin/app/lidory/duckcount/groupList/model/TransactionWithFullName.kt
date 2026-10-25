package app.lidory.duckcount.groupList.model

/** The names are null for a member this device has no name for, see [GroupMemberWithFullName.fullName]. */
data class TransactionWithFullName(
    val debtorUuid: String,
    val debtorFullName: String?,
    val creditorUuid: String,
    val creditorFullName: String?,
    val amount: String,
)
