package database.group

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GroupRepositoryImpl(
    private val groupDao: GroupDao,
) : GroupRepository {
    override suspend fun insert(group: Group) {
        groupDao.insert(groupEntity = group.toGroupEntity())
    }

    override suspend fun update(group: Group) {
        groupDao.update(groupEntity = group.toGroupEntity())
    }

    override suspend fun delete(group: Group) {
        groupDao.delete(groupEntity = group.toGroupEntity())
    }

    override suspend fun getAll(): Flow<List<Group>> {
        return groupDao.getAllAsFlow().map { groupEntities ->
            groupEntities.map { it.toGroup() }
        }
    }
}