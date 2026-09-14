package database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import eu.anifantakis.lib.ksafe.KSafe
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatform.getKoin

fun getDatabaseBuilder(context: Context): RoomDatabase.Builder<AppDatabase> {

    System.loadLibrary("sqlcipher")

    val vault: KSafe = getKoin().get(named("vault"))
    val password = getDatabasePassword(vault).toByteArray()

    val appContext = context.applicationContext
    val dbFile = appContext.getDatabasePath(AppDatabase.DATABASE_NAME)

    val factory = SupportOpenHelperFactory(password)

    return Room.databaseBuilder<AppDatabase>(
        context = appContext,
        name = dbFile.absolutePath,
    ).openHelperFactory(factory)
}

/**
 * Takes the journal and write-ahead files with it, which is the reason to go through the context
 * rather than deleting the path [getDatabaseBuilder] builds the database on.
 */
internal actual fun deleteDatabaseFile() {
    val context: Context = getKoin().get()

    context.applicationContext.deleteDatabase(AppDatabase.DATABASE_NAME)
}
