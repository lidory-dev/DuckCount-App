package app.lidory.duckcount.groupList.callBacks

import app.lidory.duckcount.groupList.model.Currency
import app.lidory.duckcount.groupList.model.Group
import app.lidory.duckcount.groupList.model.GroupCreationError
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
     * at their next sync.
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
     * Takes the [title] and [description] the user gave the group [uuid], on this device only. Its
     * members are changed from its settings, by its creator alone, see
     * [GroupSettingsCallBacks.addMembers].
     */
    suspend fun updateGroup(uuid: String, title: String, description: String)

    /**
     * Brings the groups up to date: joins the ones contacts added the user to, and sends again the
     * ones the user created that the server has not taken yet.
     *
     * Failing is not reported: the user keeps seeing what they already had, and the next refresh
     * picks up whatever did not get done.
     */
    suspend fun refreshGroups()

    /**
     * Called when the group list is in front of the user.
     */
    fun groupsSeen()
}
