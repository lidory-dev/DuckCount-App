package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * When the budget of profile key requests this device may send [peerUuid] is whole again, see
 * `contact.ProfileKeyPull.requestMissingKeys`.
 *
 * Apart from [PendingProfileKeyEntity] on purpose, as [KeyRequestBudgetEntity] is from the rows
 * it is spent on: a row goes once a read opens the contact's latest version, and kept with it,
 * the budget would be whole again with the next row, whenever reads gave one. Apart from
 * [KeyRequestBudgetEntity] too, which the expense keys asked of the same peer are spent from,
 * each under its own lock: one budget written by two passes would lose what either spent.
 */
@Entity
class ProfileKeyRequestBudgetEntity(
    @PrimaryKey(autoGenerate = false) val peerUuid: String,
    val budgetFullAt: Long,
)
