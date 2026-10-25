package database.data.repository

import database.data.local.dao.GroupDao
import database.data.mappers.toGroup
import database.data.mappers.toGroupMemberEntity
import database.data.mappers.toGroupWithMembers
import database.domain.Group
import database.domain.GroupMember
import database.domain.GroupWithMembers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

class GroupRepository(
    private val groupDao: GroupDao,
) {
    suspend fun insertMembers(groupMembers: List<GroupMember>) {
        groupDao.insertMembers(groupMemberEntities = groupMembers.map { it.toGroupMemberEntity() })
    }

    /**
     * Takes what the user edited of the group and its members, on this device only: what the
     * server and the MLS group hold of it stays as it was.
     */
    suspend fun updateWithMembers(groupWithMembers: GroupWithMembers) {
        groupDao.updateWithMembers(
            uuid = groupWithMembers.uuid,
            title = groupWithMembers.title,
            description = groupWithMembers.description,
            currency = groupWithMembers.currency,
            members = groupWithMembers.groupMembers.map { it.toGroupMemberEntity() },
        )
    }

    suspend fun removeGroupMember(userUuid: String, groupUuid: String) {
        groupDao.removeGroupMember(userUuid, groupUuid)
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
     * Emits the group [uuid] with its members, or null while there is no such group: a creation the
     * server refused deletes its group under whatever screen is open, see
     * [GroupDao.deleteRefusedCreation].
     */
    fun getWithMembers(uuid: String): Flow<GroupWithMembers?> {
        return groupDao.getWithMembers(uuid).map { groupWithMembersEntity ->
            groupWithMembersEntity?.toGroupWithMembers()
        }
    }
}
