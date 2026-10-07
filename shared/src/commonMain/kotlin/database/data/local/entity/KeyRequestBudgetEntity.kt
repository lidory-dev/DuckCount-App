package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * When the budget of key requests this device may send [creditorUuid] is whole again, see
 * `expense.OneTimeExpenseSync.requestMissingKeys`.
 *
 * Apart from [PendingExpenseKeyEntity] on purpose: those rows come and go with the server's
 * listing, and what it lists of a creditor's expenses is the creditor's to decide. Deleting
 * one and creating another hands the user a fresh row with nothing tried yet, so a budget
 * kept with the rows would be whole again whenever the creditor liked. Not with the channel
 * either, see [DirectChannelEntity]: a creditor the vault gave back after a log-out can have
 * none when they are asked, nothing having been sent to them or joined from them yet.
 */
@Entity
class KeyRequestBudgetEntity(
    @PrimaryKey(autoGenerate = false) val creditorUuid: String,
    val budgetFullAt: Long,
)
