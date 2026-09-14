package database.data.local.entity

import androidx.room.Entity

/**
 * One record of the MLS state `lib-duckcount` persists through `mls.RoomMlsStore`:
 * group states and their retained epochs, the secrets behind published key packages,
 * the signature keys pinned for peers, and the user's own signature key.
 *
 * [value] reaches this table already sealed by the library, bound to the exact
 * [namespace] and [key] it is stored under, so a row moved to another slot no longer
 * opens. The one exception is the metadata namespace, which only holds the public salt
 * the sealing key is derived with. [key] is not sealed, and pins are keyed by the
 * peer's user UUID, which is why these rows sit in the encrypted database rather than
 * in a store of their own.
 *
 * Not a data class: its generated `equals` would compare the arrays by reference, and
 * nothing compares records anyway.
 */
@Entity(primaryKeys = ["namespace", "key"])
class MlsRecordEntity(
    val namespace: String,
    val key: ByteArray,
    val value: ByteArray,
)
