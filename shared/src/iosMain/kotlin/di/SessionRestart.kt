package di

import database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.koin.core.context.loadKoinModules
import org.koin.core.context.unloadKoinModules
import org.koin.mp.KoinPlatform.getKoin
import sync.ForegroundResendScheduler
import sync.ForegroundSync

/**
 * Starts the app over within this process once its session has ended, as Android's `restartApp`
 * starts it over in a new one, which iOS has no way to do: whether the user logged out, deleted
 * their account, or the server ended the session, see `networking.DuckCountClient.endSession`.
 *
 * Ending the session dropped the key the local database is sealed with, but this process opened the
 * database with that key and would keep writing under it, everything the next session stored being
 * discarded at the next launch, MLS secrets nothing can bring back among it. So the database is
 * closed, and every object built for the session is dropped, by reloading every module but the
 * vaults', see [sessionModules]: the next one asked for opens a database of its own, finding no key
 * to open the last one with, and so on down to the screens, which the app builds again.
 *
 * What runs on its own stops first, and is waited for: the resend timer and the sync, which would
 * otherwise write to the database as it closes. The app waits beforehand for the writes the user
 * started, and takes its screens down, which stops what they read. Anything still reaching for the
 * database after it closed fails, which on iOS ends the app: once the session is over, which is
 * what a launch then finds.
 *
 * On the main thread, where the sync and the timer keep their bookkeeping, and not cancellable:
 * stopping halfway would leave the app on a closed database.
 */
suspend fun restartSession() {
    withContext(NonCancellable + Dispatchers.Main) {
        val koin = getKoin()

        koin.get<ForegroundResendScheduler>().close()
        koin.get<ForegroundSync>().close()

        koin.get<AppDatabase>().close()

        unloadKoinModules(sessionModules)
        loadKoinModules(sessionModules)
    }
}
