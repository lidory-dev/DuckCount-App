package app.lidory.duckcount.groupList.callBacks

import app.lidory.duckcount.groupList.model.ContactRequestError
import app.lidory.duckcount.groupList.model.GroupWithMembers
import app.lidory.duckcount.groupList.model.MemberRelation
import app.lidory.duckcount.groupList.model.MembershipChangeResult
import app.lidory.duckcount.groupList.model.User
import kotlinx.coroutines.flow.Flow

interface GroupSettingsCallBacks {

    /** The most members a group may have, its creator included, see [GroupListCallBacks.maxMembers]. */
    val maxMembers: Int

    fun getAuthenticatedUserUuid(): String?

    suspend fun getGroupWithMembers(groupUuid: String): Flow<GroupWithMembers>

    /**
     * Emits the name to show for [userUuid], a member of the group, as it changes, or null while
     * this device has none for them: one who is not the user's contact is named once a group hands
     * over the key to their profile, and it is read.
     */
    fun getDisplayName(userUuid: String): Flow<String?>

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

    /** Emits where the user stands with [userUuid], a member of the group, see [MemberRelation], as it changes. */
    fun getContactRelation(userUuid: String): Flow<MemberRelation>

    /**
     * Asks [userUuid], a member of the group [groupUuid] who is neither the user nor one of their
     * contacts, to become one, addressed by their UUID: the user knows no username of theirs. The
     * group vouches for who they are, so it has to be one that can, see
     * [GroupWithMembers.canVouchForMembers].
     *
     * Null once the server took the request, which [getContactRelation] then tells as sent, or
     * why it was not.
     */
    suspend fun sendContactRequest(groupUuid: String, userUuid: String): ContactRequestError?
}
