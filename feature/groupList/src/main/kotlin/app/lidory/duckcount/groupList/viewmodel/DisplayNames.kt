package app.lidory.duckcount.groupList.viewmodel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Emits the name [nameOf] tells for each of [userUuids] as they change, null for somebody this
 * device has no name for yet. A member of the group who is not the user's contact is named once a
 * group sync reads their profile with the key the group handed over, which can be well after the
 * screen opened: a name read once would never show.
 *
 * At once for nobody, which combining no flow at all would never do.
 */
fun displayNames(
    userUuids: Collection<String>,
    nameOf: (userUuid: String) -> Flow<String?>,
): Flow<Map<String, String?>> {
    val distinct = userUuids.distinct()

    if (distinct.isEmpty()) return flowOf(emptyMap())

    return combine(distinct.map { uuid -> nameOf(uuid).map { name -> uuid to name } }) { pairs -> pairs.toMap() }
}
