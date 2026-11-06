package database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import database.data.converter.CurrencyConverter
import database.data.local.dao.BeneficiaryDao
import database.data.local.dao.BlockedUserDao
import database.data.local.dao.ContactDao
import database.data.local.dao.ContactRequestDao
import database.data.local.dao.CurrencyDao
import database.data.local.dao.DirectChannelDao
import database.data.local.dao.ExpenseDao
import database.data.local.dao.GroupDao
import database.data.local.dao.GroupMemberDao
import database.data.local.dao.IdentityConflictDao
import database.data.local.dao.ListedGroupExpenseDao
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
import database.data.local.entity.BlockedUserEntity
import database.data.local.entity.ChangeFeedEntity
import database.data.local.entity.ContactEntity
import database.data.local.entity.ContactRequestEntity
import database.data.local.entity.CurrencyEntity
import database.data.local.entity.DeclinedGroupEntity
import database.data.local.entity.DirectChannelEntity
import database.data.local.entity.ExpenseEntity
import database.data.local.entity.ExpenseRevisionEntity
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupExpenseDeletionEntity
import database.data.local.entity.GroupKeyEntity
import database.data.local.entity.GroupMemberEntity
import database.data.local.entity.GroupMemberSignatureKeyEntity
import database.data.local.entity.IdentityConflictEntity
import database.data.local.entity.KeyRequestBudgetEntity
import database.data.local.entity.ListedGroupExpenseEntity
import database.data.local.entity.ListedOneTimeExpenseEntity
import database.data.local.entity.MemberProfileEntity
import database.data.local.entity.MlsRecordEntity
import database.data.local.entity.OneTimeExpenseKeyEntity
import database.data.local.entity.OneTimeExpenseSettledMarkEntity
import database.data.local.entity.OutgoingContactRequestEntity
import database.data.local.entity.OwedExpenseKeyEntity
import database.data.local.entity.PendingExpenseKeyEntity
import database.data.local.entity.PendingGroupCreationEntity
import database.data.local.entity.PendingMembershipChangeEntity
import database.data.local.entity.PendingProfileKeyEntity
import database.data.local.entity.ProfileKeyRequestBudgetEntity
import database.data.local.entity.UnreadableGroupExpenseEntity
import database.data.local.entity.UnverifiedExpenseKeyEntity
import database.data.local.entity.UserEntity
import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeEncryptedProtection
import eu.anifantakis.lib.ksafe.getOrCreateSecret
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
        GroupKeyEntity::class,
        GroupMemberSignatureKeyEntity::class,
        PendingGroupCreationEntity::class,
        PendingMembershipChangeEntity::class,
        DeclinedGroupEntity::class,
        ContactRequestEntity::class,
        OutgoingContactRequestEntity::class,
        ContactEntity::class,
        DirectChannelEntity::class,
        IdentityConflictEntity::class,
        BlockedUserEntity::class,
        OneTimeExpenseKeyEntity::class,
        UnverifiedExpenseKeyEntity::class,
        ExpenseRevisionEntity::class,
        OneTimeExpenseSettledMarkEntity::class,
        GroupExpenseDeletionEntity::class,
        PendingExpenseKeyEntity::class,
        KeyRequestBudgetEntity::class,
        PendingProfileKeyEntity::class,
        ProfileKeyRequestBudgetEntity::class,
        OwedExpenseKeyEntity::class,
        ListedOneTimeExpenseEntity::class,
        ListedGroupExpenseEntity::class,
        UnreadableGroupExpenseEntity::class,
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
    abstract val blockedUserDao: BlockedUserDao
    abstract val oneTimeExpenseKeyDao: OneTimeExpenseKeyDao
    abstract val pendingExpenseKeyDao: PendingExpenseKeyDao
    abstract val pendingProfileKeyDao: PendingProfileKeyDao
    abstract val owedExpenseKeyDao: OwedExpenseKeyDao
    abstract val listedOneTimeExpenseDao: ListedOneTimeExpenseDao
    abstract val listedGroupExpenseDao: ListedGroupExpenseDao
    abstract val mlsRecordDao: MlsRecordDao
    abstract val memberProfileDao: MemberProfileDao

    abstract val localDataDao: LocalDataDao

    companion object {
        const val DATABASE_NAME = "DuckCount.db"
    }
}

// The Room compiler generates the `actual` implementations.
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

expect fun getRoomDatabase(builder: RoomDatabase.Builder<AppDatabase>): AppDatabase

/** What the database key is called when asking KSafe for it, see [getDatabaseKey]. */
private const val DATABASE_KEY_SECRET = "database"

/**
 * The vault entry KSafe keeps [DATABASE_KEY_SECRET] in, which it names itself rather than taking
 * the caller's name: `ksafe_secret_` before the name, the entry its own refusal to replace an
 * unreadable secret says to delete. Needed apart from the secret to tell a key never made from one
 * that cannot be read, and to drop it at log-out.
 */
private const val DATABASE_KEY_VAULT_ENTRY = "ksafe_secret_$DATABASE_KEY_SECRET"

/**
 * The SQLCipher key the local database is opened with, as SQLCipher's raw key, its 32 bytes in hex
 * within `x'` and `'`. Random bytes gain nothing from a key derivation, which SQLCipher would
 * otherwise run on a passphrase for every connection it opens, about half a second each on a
 * Pixel, Room keeping several open at once.
 *
 * Made on first use by KSafe's `getOrCreateSecret`, from the platform's secure random source, and
 * kept in the vault from then on: like the root key, isolated in hardware on a device that has it,
 * and readable only while the device is unlocked. Its write is awaited before the key is handed
 * out, the database being created under it right after: a key that never reached the disk would
 * have the next launch discard everything written under it.
 *
 * Read once per process on both platforms.
 *
 * No key means the last one was dropped at log-out ([clearDatabaseKey]), which leaves a file
 * on disk sealed under a key that no longer exists anywhere: the rows the log-out deleted are gone,
 * but whatever SQLite left in freed pages is now unreadable for good. That file cannot be opened
 * with the key minted here, so it goes first and the new key starts on an empty one.
 *
 * A key the vault holds but cannot read goes the same way, file and all, which KSafe refuses to do
 * on its own. That is what a backup restored on another phone brings: the vault and the database,
 * without the keystore keys they are sealed under, which never leave the phone. The database can
 * never be opened again, so refusing would only stop the app from starting. A read that fails for
 * now, on a locked device, throws instead and discards nothing.
 *
 * Being read once per process is also why a log-out restarts the process: the one that dropped
 * the key would otherwise keep writing under it, and this would discard all of it at the next
 * launch.
 */
suspend fun getDatabaseKey(vault: KSafe): String {
    if (vault.getKeyInfo(DATABASE_KEY_VAULT_ENTRY) == null) {
        deleteDatabaseFile()
    }

    val key = try {
        vault.getOrCreateDatabaseKey()
    } catch (refusal: IllegalStateException) {
        // A read failing for now throws this too, and reading again throws it again.
        if (vault.get<String?>(DATABASE_KEY_VAULT_ENTRY, defaultValue = null) != null) throw refusal

        vault.delete(DATABASE_KEY_VAULT_ENTRY)

        deleteDatabaseFile()

        vault.getOrCreateDatabaseKey()
    }

    return "x'${key.toHexString()}'"
}

private suspend fun KSafe.getOrCreateDatabaseKey(): ByteArray = getOrCreateSecret(
    key = DATABASE_KEY_SECRET,
    size = 32,
    protection = KSafeEncryptedProtection.HARDWARE_ISOLATED,
    requireUnlockedDevice = true,
)

/**
 * Drops the key the local database is sealed with, so the file the log-out just emptied can never
 * be read again and the next account gets a database of its own.
 *
 * Suspends until the key is actually gone rather than using the fire-and-forget `deleteDirect` the
 * vault also offers: this runs in the non-cancellable tail of a log-out, where a deletion still
 * queued when the process dies would hand the next launch the previous account's key.
 */
suspend fun clearDatabaseKey() {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.delete(
        key = DATABASE_KEY_VAULT_ENTRY,
    )
}

/**
 * Removes the database and the sidecar files SQLite keeps next to it, if any are there.
 *
 * Only ever called with no readable key in the vault, where nothing on disk can be opened anyway.
 */
internal expect fun deleteDatabaseFile()
