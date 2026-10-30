package database.data.local.entity

import androidx.room.Entity

/**
 * One generation of the key of a group this device holds, see `group.GroupKeyChain`: the key the
 * group was created with, generation `group.FIRST_KEY_GENERATION`, and each its creator handed
 * over after a removal since, one generation further each time.
 *
 * Every generation is kept, the current one, [GroupEntity.groupKey], among them, and none is ever
 * replaced by the next: what was sealed under a key stays sealed under it, a group's expenses each
 * under the generation they were written in, and the device reads them with the key of that
 * generation for as long as it holds the group, the user no longer in it included. What it lacks
 * of the earlier ones it gets back through the chain of links the server keeps, see
 * `group.GroupKeyChain`; a later one only its creator hands over, over MLS.
 *
 * Gone with the group, see [database.data.local.dao.GroupDao.deleteRefusedCreation], and at log-out.
 *
 * A plain class rather than a data class: its byte array would compare by identity.
 */
@Entity(primaryKeys = ["groupUuid", "generation"])
class GroupKeyEntity(
    val groupUuid: String,

    /** Which generation of the group's keys this is, `group.FIRST_KEY_GENERATION` for the first. */
    val generation: Long,

    /** The key, 32 bytes. */
    val groupKey: ByteArray,
)
