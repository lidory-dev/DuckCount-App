package database.data.repository

import authenticationUtils.getAuthenticatedUserUuid
import database.data.local.dao.GroupDao
import database.data.mappers.toGroup
import database.data.mappers.toGroupMemberEntity
import database.data.mappers.toGroupWithMembers
import database.domain.Group
import database.domain.GroupMember
import database.domain.GroupWithMembers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

class GroupRepository(
    private val groupDao: GroupDao,
) {
    suspend fun insertMembers(groupMembers: List<GroupMember>) {
        groupDao.insertMembers(groupMemberEntities = groupMembers.map { it.toGroupMemberEntity() })
    }

    fun getAll(): Flow<List<Group>> {
        return groupDao.getAllAsFlow().map { groupEntities ->
            groupEntities.map { it.toGroup() }
        }
    }

    fun get(uuid: String): Flow<Group> {
        return groupDao.getAsFlow(uuid).filterNotNull().map { groupEntity ->
            groupEntity.toGroup()
        }
    }

    /**
     * Emits whether the user is no longer a member of the group [uuid], removed or left, which
     * stays read-only, see [database.data.local.entity.GroupEntity.isLeft], and false while there
     * is no such group.
     */
    fun isLeft(uuid: String): Flow<Boolean> {
        return groupDao.getAsFlow(uuid).map { it?.isLeft == true }
    }

    /**
     * Emits the group [uuid] with its members, as the user signed in sees it, or null while there is
     * no such group: a creation the server refused deletes its group under whatever screen is open,
     * see [GroupDao.deleteRefusedCreation].
     */
    fun getWithMembers(uuid: String): Flow<GroupWithMembers?> {
        val selfUuid = getAuthenticatedUserUuid()

        return combine(
            groupDao.getWithMembers(uuid),
            groupDao.hasPendingChangeAsFlow(groupUuid = uuid),
            groupDao.isCreatorBlockedAsFlow(groupUuid = uuid),
        ) { groupWithMembersEntity, hasPendingChange, isCreatorBlocked ->
            groupWithMembersEntity?.toGroupWithMembers(
                selfUuid = selfUuid,
                hasPendingChange = hasPendingChange,
                isCreatorBlocked = isCreatorBlocked,
            )
        }
    }
}
