package database.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import database.data.local.entity.ChangeFeedEntity
import database.data.local.entity.ListedGroupExpenseEntity
import database.data.local.entity.UnreadableGroupExpenseEntity
import kotlinx.coroutines.flow.Flow

/** The feed [ListedGroupExpenseEntity] of the group [groupUuid] is kept up to date with, see [ChangeFeedEntity]. */
internal fun groupExpensesFeedOf(groupUuid: String) = "group_expenses:$groupUuid"

/**
 * What the device keeps of the server's listing of each group's expenses, and where it has got to
 * in each group's feed of changes to it, always written together, as [ListedOneTimeExpenseDao]
 * keeps the one of one-time expenses; and which of them it could not read yet, see
 * [UnreadableGroupExpenseEntity].
 */
@Dao
interface ListedGroupExpenseDao {

    @Query("SELECT * FROM ListedGroupExpenseEntity WHERE groupUuid = :groupUuid")
    suspend fun getAll(groupUuid: String): List<ListedGroupExpenseEntity>

    /**
     * Where the device has got to in the feed of the group [groupUuid], or null when its listing
     * has to be read whole.
     */
    @Transaction
    suspend fun getFeedToken(groupUuid: String): String? = getToken(feed = groupExpensesFeedOf(groupUuid))

    /**
     * Keeps [expenses], the listing of the group [groupUuid] read whole, in place of all there was
     * of it, and [feedToken] as where it leaves the group's feed.
     */
    @Transaction
    suspend fun replaceAll(groupUuid: String, expenses: List<ListedGroupExpenseEntity>, feedToken: String) {
        deleteAll(groupUuid = groupUuid)
        upsert(expenses = expenses)
        setFeed(ChangeFeedEntity(feed = groupExpensesFeedOf(groupUuid), token = feedToken))
    }

    /**
     * Applies a page of the feed of the group [groupUuid]: [changed] as they now stand, and
     * [feedToken] as where it leaves the feed. Nothing is ever removed: a deleted expense is listed
     * with the flag saying so.
     */
    @Transaction
    suspend fun apply(groupUuid: String, changed: List<ListedGroupExpenseEntity>, feedToken: String) {
        upsert(expenses = changed)
        setFeed(ChangeFeedEntity(feed = groupExpensesFeedOf(groupUuid), token = feedToken))
    }

    /** Forgets the listing of the group [groupUuid] and where its feed was, so that it is read whole again. */
    @Transaction
    suspend fun reset(groupUuid: String) {
        deleteAll(groupUuid = groupUuid)
        deleteFeed(feed = groupExpensesFeedOf(groupUuid))
    }

    /**
     * Records [expenseUuids] as those of the group [groupUuid]'s expenses its listing holds at a
     * version, or with a deletion flag, this device cannot read yet, see
     * [UnreadableGroupExpenseEntity], in place of those it recorded before.
     */
    @Transaction
    suspend fun replaceUnreadable(groupUuid: String, expenseUuids: List<String>) {
        deleteUnreadable(groupUuid = groupUuid)
        insertUnreadable(
            expenses = expenseUuids.map { UnreadableGroupExpenseEntity(groupUuid = groupUuid, expenseUuid = it) },
        )
    }

    /**
     * Emits how many expenses of the group [groupUuid] the server lists that this device cannot read
     * yet, and shows no version of, see [UnreadableGroupExpenseEntity], as it changes.
     */
    @Query(
        """SELECT count(*) FROM UnreadableGroupExpenseEntity
        WHERE groupUuid = :groupUuid AND expenseUuid NOT IN (SELECT uuid FROM ExpenseEntity)""",
    )
    fun countUnreadableAsFlow(groupUuid: String): Flow<Int>

    @Query("SELECT token FROM ChangeFeedEntity WHERE feed = :feed")
    suspend fun getToken(feed: String): String?

    @Upsert
    suspend fun upsert(expenses: List<ListedGroupExpenseEntity>)

    @Upsert
    suspend fun setFeed(feed: ChangeFeedEntity)

    @Query("DELETE FROM ListedGroupExpenseEntity WHERE groupUuid = :groupUuid")
    suspend fun deleteAll(groupUuid: String)

    @Query("DELETE FROM ChangeFeedEntity WHERE feed = :feed")
    suspend fun deleteFeed(feed: String)

    @Query("DELETE FROM UnreadableGroupExpenseEntity WHERE groupUuid = :groupUuid")
    suspend fun deleteUnreadable(groupUuid: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUnreadable(expenses: List<UnreadableGroupExpenseEntity>)
}
