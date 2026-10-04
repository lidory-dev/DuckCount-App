package app.lidory.duckcount.push

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import authenticationUtils.SessionLeftovers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The notifications a session put up, and this app's record of them, see [StandIns] and
 * [PostedNotifications].
 *
 * Neither goes with the session on its own. The shade would go on naming the account's
 * contacts and what they shared after the account is gone from the device, and the next
 * session would start out believing stand-ins were up that are not.
 */
internal class NotificationLeftovers(private val context: Context) : SessionLeftovers {

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            NotificationManagerCompat.from(context).cancelAll()

            StandIns(context = context).clear()
            PostedNotifications(context = context).clear()
        }
    }
}
