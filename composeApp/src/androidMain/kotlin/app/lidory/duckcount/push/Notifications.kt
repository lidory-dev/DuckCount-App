package app.lidory.duckcount.push

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Posts one notification, if the user has left the app able to, and says whether it went up, for
 * the caller to record it, see [PostedNotifications].
 *
 * The check sits against the call rather than at the top of the callers: it is the form the
 * platform's own tooling recognizes, and the permission can be taken away between one
 * notification and the next.
 */
internal fun Context.postIfPermitted(tag: String, id: Int, notification: Notification): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }

    NotificationManagerCompat.from(this).notify(tag, id, notification)

    return true
}

/** Creates the channel [id], which the user sees as [name] and [description], where there are channels. */
internal fun Context.createNotificationChannel(
    id: String,
    @StringRes name: Int,
    @StringRes description: Int,
    importance: Int,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

    val channel = NotificationChannel(id, getString(name), importance).apply {
        this.description = getString(description)
    }

    getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
}
