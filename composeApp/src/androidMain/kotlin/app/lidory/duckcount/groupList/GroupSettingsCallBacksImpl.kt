package app.lidory.duckcount.groupList

import android.util.Log
import app.lidory.duckcount.GroupLimits.MAX_MEMBERS
import app.lidory.duckcount.groupList.callBacks.GroupSettingsCallBacks
import app.lidory.duckcount.groupList.model.ContactRequestError
import app.lidory.duckcount.groupList.model.GroupWithMembers
import app.lidory.duckcount.groupList.model.MemberRelation
import app.lidory.duckcount.groupList.model.MembershipChangeResult
import app.lidory.duckcount.groupList.model.User
import app.lidory.duckcount.networking.NetworkConnectivity
import database.data.repository.ContactRequestRepository
import database.data.repository.GroupRepository
import database.data.repository.UserRepository
import group.GroupMembershipChanger
import group.GroupMembershipError
import group.MembershipChangeOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import util.Result

private const val TAG = "GroupSettingsCallBacksImpl"

class GroupSettingsCallBacksImpl(
    val groupRepository: GroupRepository,
    val userRepository: UserRepository,
    private val groupMembershipChanger: GroupMembershipChanger,
    private val contactRequestRepository: ContactRequestRepository,
    private val networkConnectivity: NetworkConnectivity,
) : GroupSettingsCallBacks {

    override val maxMembers: Int = MAX_MEMBERS

    override fun getAuthenticatedUserUuid(): String? {
        return authenticationUtils.getAuthenticatedUserUuid()
    }

    override suspend fun getGroupWithMembers(groupUuid: String): Flow<GroupWithMembers> {
        return groupRepository.getWithMembers(groupUuid).filterNotNull().map { groupWithMembers ->
            groupWithMembers.toGroupListModel()
        }
    }

    override fun getUserFullName(userUuid: String): Flow<String?> {
        return userRepository.getFullName(userUuid)
    }

    override fun getInvitableUsersFiltered(filter: String): Flow<List<User>> {
        return userRepository.getInvitableFiltered(filter)
            .map { users ->
                users.map { user ->
                    user.toGroupListModel()
                }
            }
    }

    override suspend fun addMembers(groupUuid: String, userUuids: Set<String>): MembershipChangeResult =
        groupMembershipChanger.add(groupUuid = groupUuid, userUuids = userUuids)
            .toScreenResult(memberUuids = userUuids.toList(), action = "addMembers")

    override suspend fun removeMember(groupUuid: String, userUuid: String): MembershipChangeResult =
        groupMembershipChanger.remove(groupUuid = groupUuid, userUuids = setOf(userUuid))
            .toScreenResult(memberUuids = listOf(userUuid), action = "removeMember")

    override fun getContactRelation(userUuid: String): Flow<MemberRelation> =
        contactRequestRepository.relationTo(userUuid = userUuid).map { it.toMemberRelation() }

    override suspend fun sendContactRequest(groupUuid: String, userUuid: String): ContactRequestError? =
        when (val request = contactRequestRepository.sendContactRequest(userUuid = userUuid, groupUuid = groupUuid)) {
            is Result.Success -> null

            is Result.Error -> {
                Log.w(TAG, "sendContactRequest: could not send the request: ${request.error}")

                request.error.toContactRequestError(isOnline = networkConnectivity.isOnline())
            }
        }

    /**
     * What the screen is told of a change to the members of [memberUuids], in the order they were
     * picked in, logging why [action] failed.
     */
    private suspend fun Result<MembershipChangeOutcome, GroupMembershipError>.toScreenResult(
        memberUuids: List<String>,
        action: String,
    ): MembershipChangeResult = when (this) {
        is Result.Success -> data.toMembershipChangeResult()

        is Result.Error -> {
            Log.w(TAG, "$action: could not change the members: $error")

            // Hidden or not: a member who deleted their account has been forgotten for it by
            // now, which hides them and keeps their name, see
            // `message.DirectMessageSender.receiveAccountDeletions`.
            MembershipChangeResult.Failed(
                error.toMembershipChangeError(
                    isOnline = networkConnectivity.isOnline(),
                    memberUuids = memberUuids,
                    fullNameOf = { userRepository.getFullNameEvenIfHidden(uuid = it) },
                ),
            )
        }
    }
}
