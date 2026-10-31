package database.data.local.entity

import androidx.room.Entity

/**
 * One generation of the key of a group this device holds, see `group.GroupKeyChain`: the key of
 * each MLS epoch of the group's, which every member in that epoch derives alike, the generation
 * numbered as the epoch is.
 *
 * Every generation is kept, the current one, [GroupEntity.groupKey], among them, and none is ever
 * replaced by the next: what was sealed under a key stays sealed under it, a group's expenses and
 * each version of its information under the generation they were written in, and the device reads
 * them with the key of that generation for as long as it holds the group, the user no longer in it
 * included. MLS derives the key of the epoch the group is in and of no other, so each is derived as
 * the group reaches it, see `group.GroupSync`, and kept. What this device lacks of the earlier ones
 * it gets back through the chain of links the server keeps, see `group.GroupKeyChain`; a later one
 * only reading the group's Commits gives.
 *
 * Gone with the group, see [database.data.local.dao.GroupDao.deleteRefusedCreation], and at log-out.
 *
 * A plain class rather than a data class: its byte array would compare by identity.
 */
@Entity(primaryKeys = ["groupUuid", "generation"])
class GroupKeyEntity(
    val groupUuid: String,

    /** Which generation of the group's keys this is, the epoch it is the key of. */
    val generation: Long,

    /** The key, 32 bytes. */
    val groupKey: ByteArray,

    /**
     * Whether this device has yet to see a link at this generation, among those the server lists,
     * that leads back to the key of the generation before, which it holds, or to publish one of its
     * own there, see `group.GroupSync`: true for a key derived as a Commit somebody else made was
     * read, whose committer is the one who stored a link with it, which nothing else vouches for.
     * False for every other: the user's own Commit carried the link to it, a key the chain led back
     * to was the link itself, and nothing leads to the first, nor to the key a Welcome let the user
     * in at, the one before not being theirs.
     */
    val isLinkOwed: Boolean = false,

    /**
     * Whether the chain of links gave this key, see `group.GroupKeyChain`, rather than MLS, or the
     * user's copy: one the links led to, which are only their publishers' word, and so a key that
     * opens what it was asked for, see `group.recoverKeys`, but no more. A member can have the walk
     * down pass through a key of their own on its way to the right one below it, or stop at one
     * under which a version of their own opens. Such a key gives what opens under it alone, and the
     * chain is walked again for anything else of its generation, see `group.GroupKeyChain.keyOf`.
     */
    val isRecovered: Boolean = false,
)
