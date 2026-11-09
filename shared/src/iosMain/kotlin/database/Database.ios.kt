package database

import androidx.room.RoomDatabase
import androidx.sqlite.driver.NativeSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatform.getKoin

actual fun getRoomDatabase(
    builder: RoomDatabase.Builder<AppDatabase>,
): AppDatabase {
    return builder
        .setDriver(
            SqlCipherDriver(driver = NativeSQLiteDriver()) {
                runBlocking { getDatabaseKey(vault = getKoin().get(named("vault"))) }
            },
        )
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
