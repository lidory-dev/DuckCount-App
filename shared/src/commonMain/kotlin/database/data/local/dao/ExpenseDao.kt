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
import database.data.local.entity.ExpenseRevisionEntity
import database.data.local.entity.OneTimeExpenseSettledMarkEntity
import database.domain.Currency
import group.mayBeFollowedBy
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    @Transaction
    @Query("SELECT * FROM ExpenseEntity WHERE groupUuid IS NULL ORDER BY timestamp DESC")
    fun pagingSource(): PagingSource<Int, ExpenseWithBeneficiariesEntity>

    /**
     * The expenses of the group [groupUuid], newest first, deleted ones included: the group's list
     * shows them struck through.
     *
     * Two made at the same time are ordered by UUID. Read a page at a time, by offset, a list that
     * left their order to the database could show one of them on two pages and the other on none.
     */
    @Transaction
    @Query("SELECT * FROM ExpenseEntity WHERE groupUuid = :groupUuid ORDER BY timestamp DESC, uuid")
    fun groupPagingSource(groupUuid: String): PagingSource<Int, ExpenseWithBeneficiariesEntity>

    @Insert
    suspend fun insert(expenseEntity: ExpenseEntity)

    @Insert
    suspend fun insertBeneficiaries(beneficiaries: List<BeneficiaryEntity>)

    @Update
    suspend fun update(expenseEntity: ExpenseEntity)

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

    /** Which one-time expenses this device can currently read, which is what it has a key for. */
    @Query("SELECT uuid FROM ExpenseEntity WHERE groupUuid IS NULL")
    suspend fun getOneTimeExpenseUuids(): List<String>

    /** Who paid for each of [uuids], which is who a notification about them names. */
    @Query("SELECT uuid, creditorUuid FROM ExpenseEntity WHERE groupUuid IS NULL AND uuid IN (:uuids)")
    suspend fun getOneTimeCreditors(uuids: List<String>): List<ExpenseCreditor>

    /** Each one-time expense this device can currently read, as it shows it, see [ShownOneTimeExpense]. */
    @Query(
        """
        SELECT ExpenseEntity.uuid, ExpenseEntity.title, ExpenseEntity.creditorUuid, ExpenseEntity.revision,
            COALESCE(OneTimeExpenseSettledMarkEntity.isSettled, 0) AS isSettled
        FROM ExpenseEntity
        LEFT JOIN OneTimeExpenseSettledMarkEntity
            ON OneTimeExpenseSettledMarkEntity.expenseUuid = ExpenseEntity.uuid
        WHERE ExpenseEntity.groupUuid IS NULL
        """
    )
    suspend fun getShownOneTimeExpenses(): List<ShownOneTimeExpense>

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
     * when nothing newer has been shown or signed, see [ExpenseRevisionEntity]: an
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

            // A group expense's row, under the same UUID: see [storeGroupExpenses].
            if (isGroupExpense(uuid = expenseUuid)) return@forEach

            upsertOneTimeExpense(expense = expense, revision = revision)
            setNewestRevision(ExpenseRevisionEntity(expenseUuid, newestRevision = revision))
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

    @Query("SELECT newestRevision FROM ExpenseRevisionEntity WHERE expenseUuid = :expenseUuid")
    suspend fun getNewestRevision(expenseUuid: String): Long?

    @Upsert
    suspend fun setNewestRevision(revision: ExpenseRevisionEntity)

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

        setNewestRevision(ExpenseRevisionEntity(expenseUuid, newestRevision = next))

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

    /** Whether the expense [uuid] this device holds is a group's. */
    @Query("SELECT EXISTS (SELECT 1 FROM ExpenseEntity WHERE uuid = :uuid AND groupUuid IS NOT NULL)")
    suspend fun isGroupExpense(uuid: String): Boolean

    /** The expense [uuid] as this device holds it, of whatever kind, or null. */
    @Transaction
    @Query("SELECT * FROM ExpenseEntity WHERE uuid = :uuid")
    suspend fun getWithBeneficiaries(uuid: String): ExpenseWithBeneficiariesEntity?

    /**
     * The expense [uuid] of the group [groupUuid] as this device shows it, with the revision it is
     * and who wrote it, see [ExpenseEntity.authorUuid], or null.
     */
    @Transaction
    @Query("SELECT * FROM ExpenseEntity WHERE uuid = :uuid AND groupUuid = :groupUuid")
    suspend fun getGroupExpense(groupUuid: String, uuid: String): ExpenseWithBeneficiariesEntity?

    /** The revision each expense of the group [groupUuid] this device shows is at, see [ShownRevision]. */
    @Query("SELECT uuid, revision FROM ExpenseEntity WHERE groupUuid = :groupUuid AND revision IS NOT NULL")
    suspend fun getGroupExpenseRevisions(groupUuid: String): List<ShownRevision>

    /**
     * Stores each of [expenses], versions of the group [groupUuid]'s expenses opened and checked,
     * each with the revision it was signed at and who signed it, see [ExpenseEntity.revision] and
     * [ExpenseEntity.authorUuid], its beneficiaries replaced whole, and returns those stored, each
     * with the version it replaced. Runs in a single transaction.
     *
     * Only a version newer than every one this device has shown or signed of its expense is
     * stored, see [ExpenseRevisionEntity]: one older is what a server putting back a version from
     * before an edit looks like, and one at the same revision, another version under a number this
     * device has seen, what one serving members different versions under it looks like, or the
     * very version shown, listed again. And only one that may follow the version it replaces, see
     * [mayBeFollowedBy]: a settlement is deleted by its recipient, and that is all that ever
     * changes of it. Both are checked in this transaction, so that no other sync or write can
     * store a newer version between the check and the write.
     *
     * Nor is one stored over a row of another kind, or of another group: the UUIDs of one-time and
     * group expenses are derived alike, from their creator's and a nonce, so a member who used the
     * same nonce for both would have one expense of theirs replace the other on every device
     * holding both, see [replaceAllOneTimeExpenses], while the server holds each apart. Of two
     * groups the server lists the same UUID in, the one first stored keeps it: the server takes
     * none twice, so one of the two listings is made up.
     */
    @Transaction
    suspend fun storeGroupExpenses(
        groupUuid: String,
        expenses: List<ExpenseWithBeneficiariesEntity>,
    ): List<StoredGroupExpense> =
        expenses.mapNotNull { expense ->
            val expenseUuid = expense.expense.uuid
            val revision = expense.expense.revision ?: return@mapNotNull null
            val previous = getWithBeneficiaries(uuid = expenseUuid)

            if (previous != null && previous.expense.groupUuid != groupUuid) return@mapNotNull null

            val newest = getNewestRevision(expenseUuid = expenseUuid)

            if (newest != null && revision <= newest) return@mapNotNull null

            if (previous != null && !previous.mayBeFollowedBy(next = expense)) return@mapNotNull null

            upsertWithBeneficiaries(expenseWithBeneficiaries = expense)
            setNewestRevision(ExpenseRevisionEntity(expenseUuid, newestRevision = revision))

            StoredGroupExpense(expense = expense, previous = previous)
        }

    @Delete
    suspend fun delete(expenseEntity: ExpenseEntity)

    @Query("DELETE FROM ExpenseEntity WHERE uuid = :expenseUuid")
    suspend fun hardDelete(expenseUuid: String)

    @Query("SELECT count(*) FROM ExpenseEntity WHERE isDeleted = false")
    suspend fun count(): Int

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

/**
 * One one-time expense as this device shows it, as much of it as telling that it changed
 * takes, see `expense.ExpenseChanges`.
 */
class ShownOneTimeExpense(
    val uuid: String,
    val title: String,
    val creditorUuid: String,

    /**
     * The revision the version shown was signed at, which a one-time expense always has, see
     * [ExpenseEntity.revision].
     */
    val revision: Long,

    /**
     * What its creditor's newest mark shown says, false when there is none, see
     * [OneTimeExpenseSettledMarkEntity].
     */
    val isSettled: Boolean,
)

/** The revision the version of an expense this device shows is at, see [ExpenseEntity.revision]. */
class ShownRevision(
    val uuid: String,
    val revision: Long,
)

/**
 * A version of a group expense [ExpenseDao.storeGroupExpenses] stored, [expense], and the one it
 * replaced, [previous], or null for an expense new to this device: what telling that it changed
 * takes.
 */
class StoredGroupExpense(
    val expense: ExpenseWithBeneficiariesEntity,
    val previous: ExpenseWithBeneficiariesEntity?,
)
