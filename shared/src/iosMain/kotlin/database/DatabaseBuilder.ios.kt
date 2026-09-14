package database

import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

fun getDatabaseBuilder(): RoomDatabase.Builder<AppDatabase> {
    return Room.databaseBuilder<AppDatabase>(
        name = databasePath()
    )
}

/**
 * The write-ahead log and its shared-memory index sit beside the database under the same name, and
 * a fresh database left next to the previous one's journal is not a fresh database.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun deleteDatabaseFile() {
    val databasePath = databasePath()
    val fileManager = NSFileManager.defaultManager

    listOf(databasePath, "$databasePath-wal", "$databasePath-shm").forEach { path ->
        fileManager.removeItemAtPath(path, error = null)
    }
}

private fun databasePath(): String = documentDirectory() + "/${AppDatabase.DATABASE_NAME}"

@OptIn(ExperimentalForeignApi::class)
private fun documentDirectory(): String {
    val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )

    return requireNotNull(documentDirectory?.path)
}
