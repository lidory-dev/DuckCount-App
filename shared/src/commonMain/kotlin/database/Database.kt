package database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import database.data.converter.CurrencyConverter
import database.data.local.dao.BeneficiaryDao
import database.data.local.dao.ContactDao
import database.data.local.dao.ContactRequestDao
import database.data.local.dao.CurrencyDao
import database.data.local.dao.DirectChannelDao
import database.data.local.dao.ExpenseDao
import database.data.local.dao.GroupDao
import database.data.local.dao.GroupMemberDao
import database.data.local.dao.IdentityConflictDao
import database.data.local.dao.ListedOneTimeExpenseDao
import database.data.local.dao.LocalDataDao
import database.data.local.dao.MemberProfileDao
import database.data.local.dao.MlsRecordDao
import database.data.local.dao.OneTimeExpenseKeyDao
import database.data.local.dao.OwedExpenseKeyDao
import database.data.local.dao.PendingExpenseKeyDao
import database.data.local.dao.PendingProfileKeyDao
import database.data.local.dao.UserDao
import database.data.local.entity.BeneficiaryEntity
import database.data.local.entity.ChangeFeedEntity
import database.data.local.entity.ContactEntity
import database.data.local.entity.ContactRequestEntity
import database.data.local.entity.CurrencyEntity
import database.data.local.entity.DeclinedGroupEntity
import database.data.local.entity.DirectChannelEntity
import database.data.local.entity.ExpenseEntity
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupMemberEntity
import database.data.local.entity.IdentityConflictEntity
import database.data.local.entity.KeyRequestBudgetEntity
import database.data.local.entity.ListedOneTimeExpenseEntity
import database.data.local.entity.MemberProfileEntity
import database.data.local.entity.MlsRecordEntity
import database.data.local.entity.OneTimeExpenseKeyEntity
import database.data.local.entity.OneTimeExpenseRevisionEntity
import database.data.local.entity.OneTimeExpenseSettledMarkEntity
import database.data.local.entity.OutgoingContactRequestEntity
import database.data.local.entity.OwedExpenseKeyEntity
import database.data.local.entity.PendingExpenseKeyEntity
import database.data.local.entity.PendingGroupCreationEntity
import database.data.local.entity.PendingMembershipChangeEntity
import database.data.local.entity.PendingProfileKeyEntity
import database.data.local.entity.ProfileKeyRequestBudgetEntity
import database.data.local.entity.UnverifiedExpenseKeyEntity
import database.data.local.entity.UserEntity
import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeEncryptedProtection
import eu.anifantakis.lib.ksafe.KSafeWriteMode
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatform.getKoin

@Database(
    // Every entity also needs a DELETE in `LocalDataDao.deleteAll`, or a log-out leaves it behind.
    entities = [
        UserEntity::class,
        ExpenseEntity::class,
        CurrencyEntity::class,
        BeneficiaryEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        PendingGroupCreationEntity::class,
        PendingMembershipChangeEntity::class,
        DeclinedGroupEntity::class,
        ContactRequestEntity::class,
        OutgoingContactRequestEntity::class,
        ContactEntity::class,
        DirectChannelEntity::class,
        IdentityConflictEntity::class,
        OneTimeExpenseKeyEntity::class,
        UnverifiedExpenseKeyEntity::class,
        OneTimeExpenseRevisionEntity::class,
        OneTimeExpenseSettledMarkEntity::class,
        PendingExpenseKeyEntity::class,
        KeyRequestBudgetEntity::class,
        PendingProfileKeyEntity::class,
        ProfileKeyRequestBudgetEntity::class,
        OwedExpenseKeyEntity::class,
        ListedOneTimeExpenseEntity::class,
        ChangeFeedEntity::class,
        MlsRecordEntity::class,
        MemberProfileEntity::class,
    ],
    version = 1,
)
@ConstructedBy(AppDatabaseConstructor::class)
@TypeConverters(CurrencyConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract val userDao: UserDao
    abstract val expenseDao: ExpenseDao
    abstract val currencyDao: CurrencyDao
    abstract val beneficiaryDao: BeneficiaryDao
    abstract val groupDao: GroupDao
    abstract val groupMemberDao: GroupMemberDao
    abstract val contactRequestDao: ContactRequestDao
    abstract val contactDao: ContactDao
    abstract val directChannelDao: DirectChannelDao
    abstract val identityConflictDao: IdentityConflictDao
    abstract val oneTimeExpenseKeyDao: OneTimeExpenseKeyDao
    abstract val pendingExpenseKeyDao: PendingExpenseKeyDao
    abstract val pendingProfileKeyDao: PendingProfileKeyDao
    abstract val owedExpenseKeyDao: OwedExpenseKeyDao
    abstract val listedOneTimeExpenseDao: ListedOneTimeExpenseDao
    abstract val mlsRecordDao: MlsRecordDao
    abstract val memberProfileDao: MemberProfileDao

    abstract val localDataDao: LocalDataDao

    companion object {
        const val DATABASE_NAME = "DuckCount.db"
        const val DATABASE_PASSWORD_VAULT_KEY = "database_password"
    }
}

// The Room compiler generates the `actual` implementations.
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

expect fun getRoomDatabase(builder: RoomDatabase.Builder<AppDatabase>): AppDatabase

/**
 * The SQLCipher key the local database is opened with, created on first use and kept in the vault
 * from then on.
 *
 * Read once per process on both platforms.
 *
 * No key means the last one was dropped at log-out ([clearDatabasePassword]), which leaves a file
 * on disk sealed under a key that no longer exists anywhere: the rows the log-out deleted are gone,
 * but whatever SQLite left in freed pages is now unreadable for good. That file cannot be opened
 * with the key minted here, so it goes first and the new key starts on an empty one.
 *
 * Being read once per process is also why a log-out restarts the process: the one that dropped
 * the key would otherwise keep writing under it, and this would discard all of it at the next
 * launch.
 */
fun getDatabasePassword(vault: KSafe): String {
    var databasePassword = vault.getDirect(AppDatabase.DATABASE_PASSWORD_VAULT_KEY, "")

    if (databasePassword == "") {
        deleteDatabaseFile()

        databasePassword = uniffi.lib_duckcount.generateDatabaseEncryptionKey()

        vault.putDirect(
            AppDatabase.DATABASE_PASSWORD_VAULT_KEY,
            databasePassword,
            mode = KSafeWriteMode.Encrypted(
                protection = KSafeEncryptedProtection.DEFAULT,
                requireUnlockedDevice = true,
            ),
        )
    }

    return databasePassword
}

/**
 * Drops the key the local database is sealed with, so the file the log-out just emptied can never
 * be read again and the next account gets a database of its own.
 *
 * Suspends until the key is actually gone rather than using the fire-and-forget `deleteDirect` the
 * vault also offers: this runs in the non-cancellable tail of a log-out, where a deletion still
 * queued when the process dies would hand the next launch the previous account's key.
 */
suspend fun clearDatabasePassword() {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.delete(
        key = AppDatabase.DATABASE_PASSWORD_VAULT_KEY,
    )
}

/**
 * Removes the database and the sidecar files SQLite keeps next to it, if any are there.
 *
 * Only ever called with no key in the vault, where nothing on disk can be opened anyway.
 */
internal expect fun deleteDatabaseFile()
