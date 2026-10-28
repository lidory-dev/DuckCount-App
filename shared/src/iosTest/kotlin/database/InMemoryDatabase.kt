package database

import androidx.room.Room
import androidx.sqlite.driver.NativeSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * A fresh database held in memory, for a test of the queries themselves rather than of what stands
 * in for them, and closed once [test] is done.
 *
 * On the system's SQLite, which the test executable links, see the build script: the app's own
 * database runs on SQLCipher alone, see [SqlCipherDriver], and nothing this test opens is a file
 * to keep a key from.
 */
internal suspend fun <T> withInMemoryDatabase(test: suspend (AppDatabase) -> T): T {
    val database = Room.inMemoryDatabaseBuilder<AppDatabase>()
        .setDriver(NativeSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

    return try {
        test(database)
    } finally {
        database.close()
    }
}
