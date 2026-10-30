package app.lidory.duckcount.groupList.callBacks

import app.lidory.duckcount.groupList.model.Currency
import app.lidory.duckcount.groupList.model.Group
import app.lidory.duckcount.groupList.model.GroupCreationError
import app.lidory.duckcount.groupList.model.GroupEditError
import app.lidory.duckcount.groupList.model.GroupHistory
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupWithMembers
import app.lidory.duckcount.groupList.model.User
import kotlinx.coroutines.flow.Flow

interface GroupListCallBacks {

    val maxTitleLength: Int

    val maxDescriptionLength: Int

    /** The most members a group may have, the user who creates it included, as `lib-duckcount` caps it. */
    val maxMembers: Int

    fun getAllGroups(): Flow<List<Group>>

    fun getGroup(uuid: String): Flow<Group>

    /**
     * Emits the group [uuid] with its members, or null while there is no such group: a group the
     * user created goes again should the server refuse it, under whatever screen is open.
     */
    fun getGroupWithMembers(uuid: String): Flow<GroupWithMembers?>

    /**
     * Emits who the user can put in a group, whose name contains [filter]: their contacts, blocked
     * ones aside, with a name to show for them. Never the user themself, who is in every group
     * they create rather than invited to it.
     */
    fun getInvitableUsersFiltered(filter: String): Flow<List<User>>

    fun getUsersFromGroupFiltered(filter: String, groupUuid: String): Flow<List<User>>

    fun getUserFullName(uuid: String): Flow<String?>

    /** The currencies a group can be created with (single source of truth). */
    fun getCurrencies(): List<Currency>

    /** Currency selected by default when creating a new group. */
    fun getDefaultCurrency(): Currency

    /** Localized, human-readable name for an ISO 4217 currency [code] (e.g. "USD" -> "US Dollar"). */
    fun getCurrencyDisplayName(code: String): String

    /**
     * Creates the group of [members], the user among them, and sends it to the others, who join it
     * at their next sync. Its [title] and [description] are taken without the whitespace around
     * them, see [app.lidory.duckcount.groupList.viewmodel.canConfirm].
     *
     * Returns null once created, or why it could not be. Created means kept on this device: should
     * the server not have taken it yet, it is sent again until it does, and reaches its members
     * then.
     */
    suspend fun createGroup(
        title: String,
        description: String,
        currency: Currency,
        members: List<GroupMember>,
    ): GroupCreationError?

    fun getAuthenticatedUserUuid(): String?

    /**
     * Gives the group [uuid], which the user created, [title] and [description], each without the
     * whitespace around it, as a creation takes them, signed as theirs, and sends them to the
     * server, from which every member's device takes them at its next sync. Its currency stays the
     * one it was created in, and its members are changed from its settings, by its creator alone,
     * see [GroupSettingsCallBacks.addMembers].
     *
     * Returns null once the server took them, which the group then shows, or why it did not, the
     * group showing what it showed, or, refused as outdated, the newer version the server holds,
     * see [GroupEditError.Outdated]: nothing is sent again.
     */
    suspend fun updateGroup(uuid: String, title: String, description: String): GroupEditError?

    /**
     * The versions of the title and description of the group [uuid], which the user is still in,
     * that the server keeps, the current one and the last nine its creator's edits replaced, newest
     * first: each one checked against its creator's signature, as the version the group shows is.
     * Read from the server every time it is asked for, so that every member sees the same, those
     * added since included.
     */
    suspend fun getGroupHistory(uuid: String): GroupHistory

    /**
     * Brings the groups up to date: joins the ones contacts added the user to, and sends again the
     * ones the user created that the server has not taken yet.
     *
     * Failing is not reported: the user keeps seeing what they already had, and the next refresh
     * picks up whatever did not get done.
     */
    suspend fun refreshGroups()

    /**
     * Brings the expenses of the group [uuid] up to date with what its members wrote, as the group
     * opens, which the group's screens then show, those of a group the user is no longer in staying
     * as they were.
     *
     * Failing is not reported, as for [refreshGroups]: the user keeps seeing what they already had.
     */
    suspend fun refreshGroupExpenses(uuid: String)

    /**
     * Emits how many expenses of the group [uuid] its members wrote that this device cannot read
     * yet, as it changes: sealed under a key of the group it has yet to get, they are in neither
     * its list nor its balances, which the group's screen tells the user.
     */
    fun getUnreadableExpenseCount(uuid: String): Flow<Int>

    /**
     * Called when the group list is in front of the user.
     */
    fun groupsSeen()
}
