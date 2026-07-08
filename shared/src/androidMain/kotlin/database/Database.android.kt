package database

import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers

actual fun getRoomDatabase(builder: RoomDatabase.Builder<AppDatabase>): AppDatabase {
    return builder
        // The local DB is a server-seeded cache, so a destructive reset on schema
        // change is acceptable (and avoids the in-place schema change breaking installs).
        .fallbackToDestructiveMigration(dropAllTables = true)
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
