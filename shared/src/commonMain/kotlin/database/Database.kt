package database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import database.data.converter.CurrencyConverter
import database.data.local.dao.BeneficiaryDao
import database.data.local.dao.ContactRequestDao
import database.data.local.dao.CurrencyDao
import database.data.local.dao.ExpenseDao
import database.data.local.dao.GroupDao
import database.data.local.dao.GroupMemberDao
import database.data.local.dao.LocalDataDao
import database.data.local.dao.UserDao
import database.data.local.entity.BeneficiaryEntity
import database.data.local.entity.ContactRequestEntity
import database.data.local.entity.CurrencyEntity
import database.data.local.entity.ExpenseEntity
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupMemberEntity
import database.data.local.entity.UserEntity
import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeEncryptedProtection
import eu.anifantakis.lib.ksafe.KSafeWriteMode

@Database(
    // Every entity also needs a DELETE in `LocalDataDao.deleteAll`, or a log-out leaves it behind.
    entities = [
        UserEntity::class,
        ExpenseEntity::class,
        CurrencyEntity::class,
        BeneficiaryEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        ContactRequestEntity::class,
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

    abstract val localDataDao: LocalDataDao

    companion object {
        const val DATABASE_NAME = "DuckCount.db"
        const val DATABASE_PASSWORD_VAULT_KEY = "database_password"
    }
}

// The Room compiler generates the `actual` implementations.
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

expect fun getRoomDatabase(builder: RoomDatabase.Builder<AppDatabase>): AppDatabase

fun getDatabasePassword(vault: KSafe): String {
    var databasePassword = vault.getDirect(AppDatabase.DATABASE_PASSWORD_VAULT_KEY, "")

    if (databasePassword == "") {
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
