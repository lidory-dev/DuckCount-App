package database

import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers

actual fun getRoomDatabase(builder: RoomDatabase.Builder<AppDatabase>): AppDatabase {
    return builder
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
