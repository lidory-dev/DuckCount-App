package database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import database.beneficiary.BeneficiaryDao
import database.beneficiary.BeneficiaryEntity
import database.currency.CurrencyConverter
import database.currency.CurrencyDao
import database.currency.CurrencyEntity
import database.expense.ExpenseDao
import database.expense.ExpenseEntity
import database.user.UserDao
import database.user.UserEntity
import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeEncryptedProtection
import eu.anifantakis.lib.ksafe.KSafeWriteMode

@Database(
    entities = [
        UserEntity::class,
        ExpenseEntity::class,
        CurrencyEntity::class,
        BeneficiaryEntity::class,
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
            )
        )
    }

    return databasePassword
}
