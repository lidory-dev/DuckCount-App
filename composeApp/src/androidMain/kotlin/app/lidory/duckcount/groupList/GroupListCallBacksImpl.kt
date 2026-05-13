package app.lidory.duckcount.groupList

import GroupListCallBacks
import database.group.GroupRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import model.Group

class GroupListCallBacksImpl(
    val groupRepository: GroupRepository,
) : GroupListCallBacks {
    override suspend fun getAllGroups(): Flow<List<Group>> {
        return groupRepository.getAll()
            .map { groups ->
                groups.map {
                    Group(
                        uuid = it.uuid,
                        description = it.description,
                        title = it.title,
                    )
                }
            }
    }
}