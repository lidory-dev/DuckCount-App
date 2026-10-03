package database

import androidx.room.RoomDatabase
import androidx.sqlite.driver.NativeSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatform.getKoin

actual fun getRoomDatabase(
    builder: RoomDatabase.Builder<AppDatabase>,
): AppDatabase {
    return builder
        // The local DB is a server-seeded cache, so a destructive reset on schema
        // change is acceptable (and avoids the in-place schema change breaking installs).
        .fallbackToDestructiveMigration(dropAllTables = true)
        .setDriver(
            SqlCipherDriver(driver = NativeSQLiteDriver()) {
                getDatabasePassword(vault = getKoin().get(named("vault")))
            },
        )
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
