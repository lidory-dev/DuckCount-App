package database.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import database.data.local.entity.KeyRequestBudgetEntity
import database.data.local.entity.PendingExpenseKeyEntity
import database.data.local.entity.PendingExpenseKeyState

@Dao
interface PendingExpenseKeyDao {
    /**
     * Records expenses this device cannot open yet, leaving alone what it already knows
     * about the ones it has seen before.
     *
     * Deliberately not an upsert of whole rows: `firstSeenAt`, `lastRequestedAt` and
     * `requestCount` are this device's own account of what it has tried, and a sync that
     * overwrote them would put the backoff back to zero every time it ran, which is the
     * storm the backoff exists to prevent.
     */
    @Query(
        """INSERT OR IGNORE INTO PendingExpenseKeyEntity
        (expenseUuid, creditorUuid, firstSeenAt, lastRequestedAt, requestCount, state)
        VALUES (:expenseUuid, :creditorUuid, :firstSeenAt, NULL, 0, 'PENDING')""",
    )
    suspend fun insertNew(expenseUuid: String, creditorUuid: String, firstSeenAt: Long)

    @Query("SELECT * FROM PendingExpenseKeyEntity WHERE state = :state ORDER BY firstSeenAt, expenseUuid")
    suspend fun getInState(state: PendingExpenseKeyState): List<PendingExpenseKeyEntity>

    @Query(
        """UPDATE PendingExpenseKeyEntity
        SET lastRequestedAt = :requestedAt, requestCount = requestCount + 1
        WHERE expenseUuid IN (:expenseUuids)""",
    )
    suspend fun markRequested(expenseUuids: List<String>, requestedAt: Long)

    @Query("UPDATE PendingExpenseKeyEntity SET state = :state WHERE expenseUuid IN (:expenseUuids)")
    suspend fun setState(expenseUuids: List<String>, state: PendingExpenseKeyState)

    @Query("DELETE FROM PendingExpenseKeyEntity WHERE expenseUuid NOT IN (:expenseUuids)")
    suspend fun deleteNotIn(expenseUuids: List<String>)

    @Query("DELETE FROM PendingExpenseKeyEntity")
    suspend fun deleteAll()

    /** When the budget of key requests to [creditorUuid] is whole again, or null when none was ever spent. */
    @Query("SELECT budgetFullAt FROM KeyRequestBudgetEntity WHERE creditorUuid = :creditorUuid")
    suspend fun getRequestBudgetFullAt(creditorUuid: String): Long?

    @Upsert
    suspend fun setRequestBudget(budget: KeyRequestBudgetEntity)
}
