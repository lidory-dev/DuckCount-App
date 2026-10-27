package app.lidory.duckcount.groupList.callBacks

import app.lidory.duckcount.groupList.model.GroupWithMembers
import app.lidory.duckcount.groupList.model.MembershipChangeResult
import app.lidory.duckcount.groupList.model.User
import kotlinx.coroutines.flow.Flow

interface GroupSettingsCallBacks {

    /** The most members a group may have, its creator included, see [GroupListCallBacks.maxMembers]. */
    val maxMembers: Int

    fun getAuthenticatedUserUuid(): String?

    suspend fun getGroupWithMembers(groupUuid: String): Flow<GroupWithMembers>

    fun getUserFullName(userUuid: String): Flow<String?>

    /**
     * Emits who the user can put in a group, whose name contains [filter], see
     * [GroupListCallBacks.getInvitableUsersFiltered]: the members of a group among them.
     */
    fun getInvitableUsersFiltered(filter: String): Flow<List<User>>

    /**
     * Adds [userUuids], at least one of them, to the group [groupUuid], which the user created, and
     * sends the change to the server, which hands it to the members and lets the new ones join.
     */
    suspend fun addMembers(groupUuid: String, userUuids: Set<String>): MembershipChangeResult

    /**
     * Removes [userUuid], never the user, from the group [groupUuid], which the user created, and
     * sends the change to the server, which hands it to the members, them included. The group's
     * key then changes, for what the group holds from then on to be read by its members alone.
     */
    suspend fun removeMember(groupUuid: String, userUuid: String): MembershipChangeResult
}
