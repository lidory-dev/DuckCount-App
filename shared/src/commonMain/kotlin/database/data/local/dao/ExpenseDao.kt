package database.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import database.data.local.entity.BeneficiaryEntity
import database.data.local.entity.ExpenseEntity
import database.data.local.entity.ExpenseWithBeneficiariesEntity
import database.data.local.entity.OneTimeExpenseRevisionEntity
import database.data.local.entity.OneTimeExpenseSettledMarkEntity
import database.domain.Currency
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    // TODO: The same for group expenses.
    @Transaction
    @Query("SELECT * FROM ExpenseEntity WHERE groupUuid IS NULL ORDER BY timestamp DESC")
    fun pagingSource(): PagingSource<Int, ExpenseWithBeneficiariesEntity>

    @Insert
    suspend fun insert(expenseEntity: ExpenseEntity)

    @Insert
    suspend fun insertBeneficiaries(beneficiaries: List<BeneficiaryEntity>)

    @Transaction
    suspend fun insertWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiariesEntity) {
        insert(expenseEntity = expenseWithBeneficiaries.expense)
        insertBeneficiaries(beneficiaries = expenseWithBeneficiaries.beneficiaries)
    }

    @Update
    suspend fun update(expenseEntity: ExpenseEntity)

    @Upsert
    suspend fun updateBeneficiaries(beneficiaries: List<BeneficiaryEntity>)

    @Update
    @Transaction
    suspend fun updateWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiariesEntity) {
        update(expenseEntity = expenseWithBeneficiaries.expense)
        updateBeneficiaries(beneficiaries = expenseWithBeneficiaries.beneficiaries)
    }

    @Upsert
    suspend fun upsert(expenseEntity: ExpenseEntity)

    /** Insert-or-replace an expense together with its (fully replaced) beneficiaries. */
    @Transaction
    suspend fun upsertWithBeneficiaries(expenseWithBeneficiaries: ExpenseWithBeneficiariesEntity) {
        upsert(expenseEntity = expenseWithBeneficiaries.expense)
        deleteBeneficiariesFor(expenseUuid = expenseWithBeneficiaries.expense.uuid)
        insertBeneficiaries(beneficiaries = expenseWithBeneficiaries.beneficiaries)
    }

    @Query("DELETE FROM BeneficiaryEntity WHERE expenseUuid = :expenseUuid")
    suspend fun deleteBeneficiariesFor(expenseUuid: String)

    @Query("SELECT userUuid FROM BeneficiaryEntity WHERE expenseUuid = :expenseUuid")
    suspend fun getBeneficiaryUuids(expenseUuid: String): List<String>

    /** Hard-deletes an expense and its beneficiaries in a single transaction (no restore). */
    @Transaction
    suspend fun hardDeleteWithBeneficiaries(expenseUuid: String) {
        deleteBeneficiariesFor(expenseUuid = expenseUuid)
        hardDelete(expenseUuid = expenseUuid)
    }

    /** Which one-time expenses this device can currently read, which is what it has a key for. */
    @Query("SELECT uuid FROM ExpenseEntity WHERE groupUuid IS NULL")
    suspend fun getOneTimeExpenseUuids(): List<String>

    /** Who paid for each of [uuids], which is who a notification about them names. */
    @Query("SELECT uuid, creditorUuid FROM ExpenseEntity WHERE groupUuid IS NULL AND uuid IN (:uuids)")
    suspend fun getOneTimeCreditors(uuids: List<String>): List<ExpenseCreditor>

    @Query("DELETE FROM ExpenseEntity WHERE groupUuid IS NULL AND uuid IN (:uuids)")
    suspend fun deleteOneTimeExpenses(uuids: List<String>)

    /**
     * Server-authoritative reconciliation of one-time expenses: drop the local rows
     * [goneUuids] names, the ones the server no longer lists, then upsert the ones that could
     * be read. Runs in a single transaction.
     *
     * [goneUuids] is worked out against **every** expense the server listed, not only the
     * ones in [expenses]: an expense whose key has not arrived yet cannot be turned into a
     * row, and deriving what to keep from the readable ones alone would hard-delete a
     * perfectly good cached row the moment one key read hit a network blip or a device that
     * locked mid-refresh. What the server no longer lists is what goes, of what was stored
     * before the listing was read, see `OneTimeExpenseSync.refresh`.
     *
     * Each of [expenses] comes with the signed revision it was opened at, and is stored only
     * when nothing newer has been shown or signed, see [OneTimeExpenseRevisionEntity]: an
     * older one is what a server putting back an expense from before an edit looks like, and
     * the row on screen stays. Checked in this transaction, so that no other sync or edit can
     * store a newer version between the check and the write.
     */
    @Transaction
    suspend fun replaceAllOneTimeExpenses(
        expenses: List<Pair<ExpenseWithBeneficiariesEntity, Long>>,
        goneUuids: List<String>,
    ) {
        deleteOneTimeExpenses(uuids = goneUuids)

        expenses.forEach { (expense, revision) ->
            val expenseUuid = expense.expense.uuid
            val newest = getNewestRevision(expenseUuid = expenseUuid)

            if (newest != null && revision < newest) return@forEach

            upsertOneTimeExpense(expense = expense, revision = revision)
            setNewestRevision(OneTimeExpenseRevisionEntity(expenseUuid, newestRevision = revision))
        }
    }

    /**
     * Stores [expense] as the version of a one-time expense signed at [revision], its
     * beneficiaries replaced whole, see [ExpenseEntity.revision].
     */
    @Transaction
    suspend fun upsertOneTimeExpense(expense: ExpenseWithBeneficiariesEntity, revision: Long) {
        upsertWithBeneficiaries(
            expenseWithBeneficiaries = expense.copy(expense = expense.expense.copy(revision = revision)),
        )
    }

    /** The one-time expense [uuid] as this device shows it, with the revision it is, or null. */
    @Transaction
    @Query("SELECT * FROM ExpenseEntity WHERE uuid = :uuid AND groupUuid IS NULL")
    suspend fun getOneTimeExpense(uuid: String): ExpenseWithBeneficiariesEntity?

    @Query("SELECT newestRevision FROM OneTimeExpenseRevisionEntity WHERE expenseUuid = :expenseUuid")
    suspend fun getNewestRevision(expenseUuid: String): Long?

    @Upsert
    suspend fun setNewestRevision(revision: OneTimeExpenseRevisionEntity)

    /**
     * Reserves the revision an edit of the one-time expense [expenseUuid] is signed at: one
     * more than the newest this device has shown or signed. Null when it has done neither.
     *
     * Recorded before the edit is sent, so that one whose response never arrives, although
     * the server stored it, cannot be followed by a second, different version under the same
     * number, which participants could then be switched between.
     */
    @Transaction
    suspend fun reserveNextRevision(expenseUuid: String): Long? {
        val next = (getNewestRevision(expenseUuid = expenseUuid) ?: return null) + 1

        setNewestRevision(OneTimeExpenseRevisionEntity(expenseUuid, newestRevision = next))

        return next
    }

    /**
     * Stores each of [marks], verified marks of the one-time expenses they name, unless this
     * device has shown one as new for its expense, see
     * [OneTimeExpenseSettledMarkEntity.isReplacedBy]: an older one is what a server putting
     * back a mark from before the newest looks like. Checked in this transaction, so that no
     * other sync can store a newer one between the check and the write.
     */
    @Transaction
    suspend fun storeSettledMarks(marks: List<OneTimeExpenseSettledMarkEntity>) {
        marks.forEach { mark ->
            val stored = getSettledMark(expenseUuid = mark.expenseUuid)

            if (stored == null || stored.isReplacedBy(markedAt = mark.markedAt)) {
                setSettledMark(mark = mark)
            }
        }
    }

    @Query("SELECT * FROM OneTimeExpenseSettledMarkEntity WHERE expenseUuid = :expenseUuid")
    suspend fun getSettledMark(expenseUuid: String): OneTimeExpenseSettledMarkEntity?

    @Upsert
    suspend fun setSettledMark(mark: OneTimeExpenseSettledMarkEntity)

    @Delete
    suspend fun delete(expenseEntity: ExpenseEntity)

    @Query("DELETE FROM ExpenseEntity WHERE uuid = :expenseUuid")
    suspend fun hardDelete(expenseUuid: String)

    @Query("UPDATE ExpenseEntity SET isDeleted = true WHERE uuid = :expenseUuid")
    suspend fun delete(expenseUuid: String)

    @Query("UPDATE ExpenseEntity SET isDeleted = false WHERE uuid = :expenseUuid")
    suspend fun restore(expenseUuid: String)

    @Query("SELECT count(*) FROM ExpenseEntity WHERE isDeleted = false")
    suspend fun count(): Int

    @Transaction
    @Query("SELECT * FROM ExpenseEntity WHERE groupUuid = :groupUuid")
    fun getAllFromGroupAsFlow(groupUuid: String): Flow<List<ExpenseWithBeneficiariesEntity>>

    @Transaction
    @Query("SELECT * FROM ExpenseEntity WHERE groupUuid = :groupUuid AND isDeleted = false")
    fun getAllFromGroupWithoutDeletedOnesAsFlow(groupUuid: String): Flow<List<ExpenseWithBeneficiariesEntity>>

    @Transaction
    @Query("SELECT * FROM ExpenseEntity WHERE groupUuid IS NULL")
    fun getAllWithoutGroupAsFlow(): Flow<List<ExpenseWithBeneficiariesEntity>>

    @Transaction
    @Query("SELECT * FROM ExpenseEntity WHERE isDeleted = false")
    fun getAllWithBeneficiariesAsFlow(): Flow<List<ExpenseWithBeneficiariesEntity>>

    @Transaction
    @Query("SELECT * FROM ExpenseEntity WHERE uuid = :uuid")
    fun getWithBeneficiariesAsFlow(uuid: String): Flow<ExpenseWithBeneficiariesEntity?>

    @Query("SELECT currency FROM ExpenseEntity WHERE uuid = :expenseUuid")
    fun getCurrency(expenseUuid: String): Flow<Currency?>
}

/** One expense and who paid for it, which is all a notification needs of it. */
class ExpenseCreditor(
    val uuid: String,
    val creditorUuid: String,
)
