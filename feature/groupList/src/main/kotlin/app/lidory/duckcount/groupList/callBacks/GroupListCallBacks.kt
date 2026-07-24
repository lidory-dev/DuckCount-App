package app.lidory.duckcount.groupList.callBacks

import app.lidory.duckcount.groupList.model.Currency
import app.lidory.duckcount.groupList.model.Group
import app.lidory.duckcount.groupList.model.GroupMember
import app.lidory.duckcount.groupList.model.GroupWithMembers
import app.lidory.duckcount.groupList.model.User
import kotlinx.coroutines.flow.Flow

interface GroupListCallBacks {

    val maxTitleLength: Int

    val maxDescriptionLength: Int

    fun getAllGroups(): Flow<List<Group>>

    fun getGroup(uuid: String): Flow<Group>

    fun getGroupWithMembers(uuid: String): Flow<GroupWithMembers>

    fun getUsersFiltered(filter: String): Flow<List<User>>

    fun getUsersFromGroupFiltered(filter: String, groupUuid: String): Flow<List<User>>

    fun getUserFullName(uuid: String): Flow<String?>

    /** The currencies a group can be created with (single source of truth). */
    fun getCurrencies(): List<Currency>

    /** Currency selected by default when creating a new group. */
    fun getDefaultCurrency(): Currency

    /** Localized, human-readable name for an ISO 4217 currency [code] (e.g. "USD" -> "US Dollar"). */
    fun getCurrencyDisplayName(code: String): String

    suspend fun createGroup(title: String, description: String, currency: Currency, members: List<GroupMember>)

    fun getAuthenticatedUserUuid(): String?

    suspend fun updateGroup(groupWithMembers: GroupWithMembers)
}
