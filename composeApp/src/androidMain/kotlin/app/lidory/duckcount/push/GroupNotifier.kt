package app.lidory.duckcount.push

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.lidory.duckcount.EXTRA_OPEN_GROUPS
import app.lidory.duckcount.EXTRA_OPEN_GROUP_UUID
import app.lidory.duckcount.MainActivity
import app.lidory.duckcount.R
import group.GroupArrival
import group.GroupDeletion
import group.GroupRemoval

/**
 * The channel the user sees, and can turn off on its own.
 *
 * Apart from the contacts and expenses ones, for the reason `ExpenseNotifier` gives: a user who
 * tires of being added to groups should not stop hearing about requests or expenses.
 */
private const val GROUPS_CHANNEL = "groups"

/** What these notifications are about, so a screen showing the groups can take them all down. */
private const val GROUP_TAG = "group"

/**
 * What tells the intent of each form that names nobody apart from the named ones', see
 * [GroupNotifier.namelessNotification], which only ever goes up as their public version: one for
 * the user added to a group, one for the user removed from one, one for a group deleted.
 */
private const val UNNAMED_GROUP_REQUEST_CODE = 7
private const val UNNAMED_GROUP_REMOVAL_REQUEST_CODE = 8
private const val UNNAMED_GROUP_DELETION_REQUEST_CODE = 9

/**
 * Tells the user about the groups a contact added them to, those they were removed from, and those
 * their owner deleted, one notification per group.
 *
 * What a push carries is a signal with no content, so everything said here comes from what the
 * group sync read and decrypted for itself: who wrote the Welcome, or the Commit removing the
 * user, and the title the group opened to, see [group.GroupSync]. Only a contact can add the user,
 * the sync declining anybody else's group without a word. Whoever made the Commit is who removed
 * them, named as MLS says, see [GroupRemoval.removerUuid]: the group's creator, for anything but
 * the removal of a member who departed, which any member makes. A group the user left is never one
 * the sync reports them removed from, see [group.GroupLeaver], and one they created never one it
 * reports deleted, see [group.GroupDeleter]: they know already. Who deleted a group is its creator,
 * the one person who can, by deleting it or their account, see [GroupDeletion.deleterUuid].
 *
 * Nothing goes up while the device is locked. Being added to a group, removed from one, or having
 * it deleted, asks
 * nothing of the user, and the signal alone could not say which group, or whose: the sync that
 * names both waits for the unlock, and puts it up then. It rings: somebody else put the user in a
 * group, or took them out of one, which is news to them, unlike a contact accepting a request they
 * sent themselves, see `ContactNotifier`.
 */
class GroupNotifier(
    private val context: Context,
) {
    private val posted = PostedNotifications(context = context)

    /** Puts one notification up per group the user was added to. */
    fun notify(arrivals: List<GroupArrival>) {
        for (arrival in arrivals) {
            val text = when (val adderFullName = arrival.adderFullName) {
                null -> context.getString(R.string.push_group_added_text_unknown, arrival.title)
                else -> context.getString(R.string.push_group_added_text, adderFullName, arrival.title)
            }

            post(
                groupUuid = arrival.groupUuid,
                title = R.string.push_group_added_title,
                text = text,
                publicVersion = namelessNotification(
                    title = R.string.push_group_added_title,
                    text = R.string.push_group_added_text_locked,
                    requestCode = UNNAMED_GROUP_REQUEST_CODE,
                ),
            )
        }
    }

    /** Puts one notification up per group the user was removed from. */
    fun notifyRemovals(removals: List<GroupRemoval>) {
        for (removal in removals) {
            val text = when (val removerFullName = removal.removerFullName) {
                null -> context.getString(R.string.push_group_removed_text_unknown, removal.title)
                else -> context.getString(R.string.push_group_removed_text, removerFullName, removal.title)
            }

            post(
                groupUuid = removal.groupUuid,
                title = R.string.push_group_removed_title,
                text = text,
                publicVersion = namelessNotification(
                    title = R.string.push_group_removed_title,
                    text = R.string.push_group_removed_text_locked,
                    requestCode = UNNAMED_GROUP_REMOVAL_REQUEST_CODE,
                ),
            )
        }
    }

    /** Puts one notification up per group found deleted. */
    fun notifyDeletions(deletions: List<GroupDeletion>) {
        for (deletion in deletions) {
            val text = when (val deleterFullName = deletion.deleterFullName) {
                null -> context.getString(R.string.push_group_deleted_text_unknown, deletion.title)
                else -> context.getString(R.string.push_group_deleted_text, deleterFullName, deletion.title)
            }

            post(
                groupUuid = deletion.groupUuid,
                title = R.string.push_group_deleted_title,
                text = text,
                publicVersion = namelessNotification(
                    title = R.string.push_group_deleted_title,
                    text = R.string.push_group_deleted_text_locked,
                    requestCode = UNNAMED_GROUP_DELETION_REQUEST_CODE,
                ),
            )
        }
    }

    /**
     * Takes down every notification about a group the user was added to, removed from, or found deleted.
     *
     * For the group list, once it is on screen: the groups are there to read, and anything these
     * said about them the user is already reading.
     */
    fun clearGroups() {
        val manager = NotificationManagerCompat.from(context)

        posted.takeAll(tag = GROUP_TAG).forEach { manager.cancel(GROUP_TAG, it) }
    }

    /**
     * Puts up the notification about the group [groupUuid], [title] and [text], with
     * [publicVersion] standing for it on a lockscreen that hides notification content.
     */
    private fun post(groupUuid: String, @StringRes title: Int, text: String, publicVersion: Notification) {
        context.createNotificationChannel(
            id = GROUPS_CHANNEL,
            name = R.string.push_channel_groups_name,
            description = R.string.push_channel_groups_description,
            importance = NotificationManager.IMPORTANCE_DEFAULT,
        )

        val notification = NotificationCompat.Builder(context, GROUPS_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(title))
            .setContentText(text)
            .setContentIntent(contentIntent(requestCode = groupUuid.hashCode(), groupUuid = groupUuid))
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            // Who put the user in which group, or took them out, is their business and nobody
            // else's, so a lockscreen set to hide notification content shows the nameless form.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .build()

        // One per group, saying what was last heard of it: the user added back to a group they
        // were removed from is in it, and one removed from a group they were added to is not.
        val id = groupUuid.hashCode()

        if (context.postIfPermitted(tag = GROUP_TAG, id = id, notification = notification)) {
            posted.record(tag = GROUP_TAG, id = id)
        }
    }

    /**
     * The form that names nobody and no group, [title] and [text]: the user was added to a group,
     * removed from one, or one was deleted.
     *
     * Carried by every named one as its [NotificationCompat.Builder.setPublicVersion], which is
     * what a lockscreen shows for a user who has asked to keep notification content hidden.
     * Without it Android has nothing safe to fall back on and shows the app's name alone.
     */
    private fun namelessNotification(@StringRes title: Int, @StringRes text: Int, requestCode: Int): Notification =
        NotificationCompat.Builder(context, GROUPS_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(title))
            .setContentText(context.getString(text))
            .setContentIntent(contentIntent(requestCode = requestCode))
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            // It carries no name and no title, so there is nothing here to hide.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build()

    /**
     * Opens the app on the group list, which also takes down whatever other group the user was
     * told about, see [clearGroups], and on [groupUuid] over it, for a notification naming a group.
     *
     * [requestCode] has to differ from one group to another: the extras are no part of what tells
     * two pending intents apart, so sharing one would have every notification open the last group.
     */
    private fun contentIntent(requestCode: Int, groupUuid: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK

            putExtra(EXTRA_OPEN_GROUPS, true)
            putExtra(EXTRA_OPEN_GROUP_UUID, groupUuid)
        }

        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
