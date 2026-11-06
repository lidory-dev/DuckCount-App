package database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL

/**
 * Opens every connection to the local database already keyed for SQLCipher, before Room runs
 * anything on it.
 *
 * The SQLite underneath is whatever the app links, which is SQLCipher through the iOS app's
 * Swift package. A connection that is not on SQLCipher is refused rather than used.
 */
internal class SqlCipherDriver(
    private val driver: SQLiteDriver,
    readKey: () -> String,
) : SQLiteDriver by driver {

    /**
     * Read when the first connection is about to be made, and not before: reading it deletes
     * the file of a database whose key is gone, see [getDatabaseKey], which has to happen
     * while no connection has it open.
     */
    private val key by lazy(readKey)

    override fun open(fileName: String): SQLiteConnection {
        val key = key

        val connection = driver.open(fileName)

        try {
            // Double quotes, the raw key form holding single ones.
            connection.execSQL("PRAGMA key = \"$key\"")

            val isSqlCipher = connection.prepare("PRAGMA cipher_version").use { it.step() }

            check(isSqlCipher) { "The SQLite this build links is not SQLCipher" }
        } catch (throwable: Throwable) {
            connection.close()

            throw throwable
        }

        return connection
    }
}
