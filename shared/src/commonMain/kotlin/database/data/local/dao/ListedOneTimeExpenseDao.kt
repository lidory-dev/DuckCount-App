package database.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import database.data.local.entity.ChangeFeedEntity
import database.data.local.entity.ListedOneTimeExpenseEntity

/** The feed [ListedOneTimeExpenseEntity] is kept up to date with, see [ChangeFeedEntity]. */
private const val ONE_TIME_EXPENSES_FEED = "one_time_expenses"

/**
 * What the device keeps of the server's listing of one-time expenses, and where it has
 * got to in the feed of changes to it, always written together.
 */
@Dao
interface ListedOneTimeExpenseDao {

    @Query("SELECT * FROM ListedOneTimeExpenseEntity")
    suspend fun getAll(): List<ListedOneTimeExpenseEntity>

    /** Where the device has got to in the feed, or null when the listing has to be read whole. */
    @Query("SELECT token FROM ChangeFeedEntity WHERE feed = '$ONE_TIME_EXPENSES_FEED'")
    suspend fun getFeedToken(): String?

    /** Keeps [expenses], a listing read whole, in place of all there was, and [feedToken] as where it leaves the feed. */
    @Transaction
    suspend fun replaceAll(expenses: List<ListedOneTimeExpenseEntity>, feedToken: String) {
        deleteAll()
        upsert(expenses = expenses)
        setFeed(ChangeFeedEntity(feed = ONE_TIME_EXPENSES_FEED, token = feedToken))
    }

    /**
     * Applies a page of the feed: [changed] as they now stand, [removedUuids] gone, and
     * [feedToken] as where it leaves the feed.
     */
    @Transaction
    suspend fun apply(
        changed: List<ListedOneTimeExpenseEntity>,
        removedUuids: List<String>,
        feedToken: String,
    ) {
        upsert(expenses = changed)
        delete(expenseUuids = removedUuids)
        setFeed(ChangeFeedEntity(feed = ONE_TIME_EXPENSES_FEED, token = feedToken))
    }

    /** Forgets the listing and where the feed was, so that it is read whole again. */
    @Transaction
    suspend fun reset() {
        deleteAll()
        deleteFeed()
    }

    @Upsert
    suspend fun upsert(expenses: List<ListedOneTimeExpenseEntity>)

    @Upsert
    suspend fun setFeed(feed: ChangeFeedEntity)

    @Query("DELETE FROM ListedOneTimeExpenseEntity WHERE expenseUuid IN (:expenseUuids)")
    suspend fun delete(expenseUuids: List<String>)

    @Query("DELETE FROM ListedOneTimeExpenseEntity")
    suspend fun deleteAll()

    @Query("DELETE FROM ChangeFeedEntity WHERE feed = '$ONE_TIME_EXPENSES_FEED'")
    suspend fun deleteFeed()
}
