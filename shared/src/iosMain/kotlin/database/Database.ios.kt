package database

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.NativeSQLiteDriver
import androidx.sqlite.execSQL
import eu.anifantakis.lib.ksafe.KSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatform.getKoin

actual fun getRoomDatabase(
    builder: RoomDatabase.Builder<AppDatabase>,
): AppDatabase {
    return builder
        .setDriver(NativeSQLiteDriver())
        .addCallback(object : RoomDatabase.Callback() {
            override fun onOpen(connection: SQLiteConnection) {
                val vault: KSafe = getKoin().get(named("vault"))
                connection.execSQL("PRAGMA key = '${getDatabasePassword(vault)}'")
            }
        })
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
