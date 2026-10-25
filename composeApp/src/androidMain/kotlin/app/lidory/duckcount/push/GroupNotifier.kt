package app.lidory.duckcount.push

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.lidory.duckcount.EXTRA_OPEN_GROUPS
import app.lidory.duckcount.MainActivity
import app.lidory.duckcount.R
import group.GroupArrival

/**
 * The channel the user sees, and can turn off on its own.
 *
 * Apart from the contacts and expenses ones, for the reason `ExpenseNotifier` gives: a user who
 * tires of being added to groups should not stop hearing about requests or expenses.
 */
private const val GROUPS_CHANNEL = "groups"

/** What these notifications are about, so a screen showing the groups can take them all down. */
private const val GROUP_TAG = "group-joined"

/**
 * What tells the intent of the form that names nobody apart from the named ones', see
 * [GroupNotifier.namelessNotification], which only ever goes up as their public version.
 */
private const val UNNAMED_GROUP_REQUEST_CODE = 7

/**
 * Tells the user about the groups a contact added them to, one notification per group.
 *
 * What a push carries is a signal with no content, so everything said here comes from what the
 * group sync read and decrypted for itself: who wrote the Welcome, and the title it opened, see
 * [group.GroupSync]. Only a contact can be the one, the sync declining anybody else's group
 * without a word.
 *
 * Nothing goes up while the device is locked. Being added to a group asks nothing of the user, and
 * the signal alone could not say which group, or whose: the sync that names both waits for the
 * unlock, and puts it up then. It rings: somebody else put the user in a group, which is news
 * to them, unlike a contact accepting a request they sent themselves, see `ContactNotifier`.
 */
class GroupNotifier(
    private val context: Context,
) {
    private val posted = PostedNotifications(context = context)

    /** Puts one notification up per group the user was added to. */
    fun notify(arrivals: List<GroupArrival>) {
        if (arrivals.isEmpty()) return

        context.createNotificationChannel(
            id = GROUPS_CHANNEL,
            name = R.string.push_channel_groups_name,
            description = R.string.push_channel_groups_description,
            importance = NotificationManager.IMPORTANCE_DEFAULT,
        )

        for (arrival in arrivals) {
            val text = when (val adderFullName = arrival.adderFullName) {
                null -> context.getString(R.string.push_group_added_text_unknown, arrival.title)
                else -> context.getString(R.string.push_group_added_text, adderFullName, arrival.title)
            }

            val notification = NotificationCompat.Builder(context, GROUPS_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.push_group_added_title))
                .setContentText(text)
                .setContentIntent(contentIntent(requestCode = arrival.groupUuid.hashCode()))
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                // Who put the user in which group is their business and nobody else's, so a
                // lockscreen set to hide notification content shows the nameless form instead.
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(namelessNotification())
                .setAutoCancel(true)
                .build()

            // One per group: a group is joined once, so this is only ever replaced by itself.
            val id = arrival.groupUuid.hashCode()

            if (context.postIfPermitted(tag = GROUP_TAG, id = id, notification = notification)) {
                posted.record(tag = GROUP_TAG, id = id)
            }
        }
    }

    /**
     * Takes down every notification about a group the user was added to.
     *
     * For the group list, once it is on screen: the groups are there to read, and anything these
     * said about them the user is already reading.
     */
    fun clearJoinedGroups() {
        val manager = NotificationManagerCompat.from(context)

        posted.takeAll(tag = GROUP_TAG).forEach { manager.cancel(GROUP_TAG, it) }
    }

    /**
     * The form that names nobody and no group: the user was added to a group.
     *
     * Carried by every named one as its [NotificationCompat.Builder.setPublicVersion], which is
     * what a lockscreen shows for a user who has asked to keep notification content hidden.
     * Without it Android has nothing safe to fall back on and shows the app's name alone.
     */
    private fun namelessNotification(): Notification =
        NotificationCompat.Builder(context, GROUPS_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.push_group_added_title))
            .setContentText(context.getString(R.string.push_group_added_text_locked))
            .setContentIntent(contentIntent(requestCode = UNNAMED_GROUP_REQUEST_CODE))
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            // It carries no name and no title, so there is nothing here to hide.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build()

    /**
     * Opens the app on the group list, where the group is, which also takes down whatever other
     * group the user was told about, see [clearJoinedGroups].
     */
    private fun contentIntent(requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK

            putExtra(EXTRA_OPEN_GROUPS, true)
        }

        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
