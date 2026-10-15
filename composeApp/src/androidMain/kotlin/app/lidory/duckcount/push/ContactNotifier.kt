package app.lidory.duckcount.push

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import app.lidory.duckcount.EXTRA_OPEN_CONTACT_REQUESTS
import app.lidory.duckcount.MainActivity
import app.lidory.duckcount.R
import contact.ContactArrival
import push.PushSignal

/** The channel the user sees, and can turn off on its own. */
private const val CONTACTS_CHANNEL = "contacts"

/**
 * What a notification is about, so that a screen showing that news can take down every
 * notification carrying it without having to know who they are about.
 */
private const val REQUEST_TAG = "contact-request"

private const val ACCEPTED_TAG = "contact-accepted"

/** The channel that only exists to satisfy the system while a sync runs. */
private const val SYNC_CHANNEL = "sync"

/** The id of the notification that stands for a running sync. */
private const val SYNC_NOTIFICATION = 1

/**
 * The id of the notification posted from a signal alone, without a name to put on it.
 * Fixed, so a second stand-in replaces the first rather than joining it, and used for
 * nothing else: a named notification under it would be replaced by the next stand-in.
 */
private const val UNNAMED_REQUEST_NOTIFICATION = 2

/**
 * Tells the user who turned up.
 *
 * What a push carries is a signal with no content, so everything said here comes from
 * what the device read and decrypted for itself. That is also why there is nothing to
 * say about a contact who removed the user: their client sends that notice, and being
 * told about it would not be useful.
 */
class ContactNotifier(
    private val context: Context,
) {
    private val standIns = StandIns(context = context)

    private val posted = PostedNotifications(context = context)

    /**
     * Says what a signal said, for a device too locked to find out more.
     *
     * Only a request can be announced this way. An acceptance needs nothing from the
     * user, see [isSilent], so it waits for the unlock, and the sync that follows puts it
     * up with a name, in silence. A `message` may be a notice that somebody removed the
     * user, and saying "something happened" when it might be that would be worse than
     * saying nothing: the sync says what it was as soon as the device is unlocked.
     */
    fun notifyWhileLocked(signal: PushSignal) {
        if (signal.kind != PushSignal.Kind.CONTACT_REQUEST) return

        createChannel(
            id = CONTACTS_CHANNEL,
            name = R.string.push_channel_contacts_name,
            description = R.string.push_channel_contacts_description,
            importance = NotificationManager.IMPORTANCE_DEFAULT,
        )

        post(
            tag = REQUEST_TAG,
            id = UNNAMED_REQUEST_NOTIFICATION,
            notification = namelessNotification(kind = ContactArrival.Kind.REQUEST),
        )

        standIns.markUp(kind = StandIns.Kind.CONTACT_REQUEST)
    }

    /**
     * Puts one notification up per person who turned up.
     *
     * A request the user has already been told about arrives here with a name on it at
     * last, and that is the same news rather than more of it. So the first one takes the
     * stand-in's place: it goes up in silence, under the id of the person it names, and the
     * stand-in comes down, rather than a second notification ringing again beside it. What
     * the stand-in stood for and the sync could not name is left alone, since it is still
     * waiting to be named.
     *
     * Everything else rings, except an acceptance: see [isSilent].
     */
    fun notify(arrivals: List<ContactArrival>) {
        if (arrivals.isEmpty()) return

        // The record only goes with a request to take the stand-in over. An acceptance
        // that came in alone, with the requests failing to refresh for instance, leaves
        // it for the sync that does bring the request in.
        var standInIsFree = arrivals.any { it.kind == ContactArrival.Kind.REQUEST } &&
            standIns.takeDown(kind = StandIns.Kind.CONTACT_REQUEST)

        createChannel(
            id = CONTACTS_CHANNEL,
            name = R.string.push_channel_contacts_name,
            description = R.string.push_channel_contacts_description,
            importance = NotificationManager.IMPORTANCE_DEFAULT,
        )

        for (arrival in arrivals) {
            val takesOverStandIn = standInIsFree && arrival.kind == ContactArrival.Kind.REQUEST

            if (takesOverStandIn) standInIsFree = false

            val notification = NotificationCompat.Builder(context, CONTACTS_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(titleOf(arrival)))
                .setContentText(context.getString(textOf(arrival), arrival.fullName))
                .setContentIntent(
                    contentIntent(
                        requestUuid = arrival.userUuid.hashCode(),
                        openRequests = arrival.kind == ContactArrival.Kind.REQUEST,
                    ),
                )
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                // Who it is from is the user's business and nobody else's, so a lockscreen
                // set to hide notification content shows the nameless form instead.
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(namelessNotification(kind = arrival.kind))
                // In the stand-in's place it would be ringing twice for one request: the
                // stand-in rang for it already.
                .setSilent(takesOverStandIn || arrival.isSilent())
                .setAutoCancel(true)
                .build()

            // One notification per person, replaced rather than stacked: a second request
            // from somebody who already has one pending is the same news. Never under the
            // stand-in's id, even taking its place: the next stand-in goes up there, and
            // would replace it before it was read.
            post(tag = tagOf(arrival), id = arrival.userUuid.hashCode(), notification = notification)

            // Only once the named one is up, so that there is no moment with neither.
            if (takesOverStandIn) {
                NotificationManagerCompat.from(context)
                    .cancel(REQUEST_TAG, UNNAMED_REQUEST_NOTIFICATION)
            }
        }
    }

    /**
     * Takes down every notification about a request waiting to be answered.
     *
     * The requests are on screen, so anything a notification could say about them the
     * user is already reading.
     */
    fun clearContactRequests() {
        clear(tag = REQUEST_TAG)

        standIns.takeDown(kind = StandIns.Kind.CONTACT_REQUEST)
    }

    /** Takes down every notification about somebody who accepted a request. */
    fun clearAcceptedContacts() = clear(tag = ACCEPTED_TAG)

    /**
     * Takes down what was said about [userUuids] accepting a request.
     *
     * For the ones who removed the user again while the notification was still up: it
     * announced a contact they no longer have, and tapping it would open a contact that
     * is gone.
     *
     * Their ids stay in the record [clear] reads. Taking one down a second time does
     * nothing, and a later notification under the same id is one [clear] should take down.
     */
    fun forget(userUuids: Collection<String>) {
        val manager = NotificationManagerCompat.from(context)

        for (userUuid in userUuids) {
            manager.cancel(ACCEPTED_TAG, userUuid.hashCode())
        }
    }

    /** Takes down everything this app put up under [tag], from its own record of it. */
    private fun clear(tag: String) {
        val manager = NotificationManagerCompat.from(context)

        posted.takeAll(tag = tag).forEach { manager.cancel(tag, it) }
    }

    /**
     * Whether this arrival goes up without a sound.
     *
     * Somebody asking to be a contact is asking the user for something, and waits on them
     * until they answer: that is worth a sound. Somebody accepting is the other end of a
     * request the user sent themselves, needs nothing from them, and is there to be read
     * whenever they next look.
     */
    private fun ContactArrival.isSilent(): Boolean = kind == ContactArrival.Kind.CONTACT

    /**
     * The form that names nobody: somebody wants to be a contact, or somebody accepted.
     *
     * Posted on its own while the device is locked, and carried by every named one as its
     * [NotificationCompat.Builder.setPublicVersion], which is what a lockscreen shows for a
     * user who has asked to keep notification content hidden. Without it Android has
     * nothing safe to fall back on and shows the app's name alone, which tells them only
     * that DuckCount wanted something.
     */
    private fun namelessNotification(kind: ContactArrival.Kind): Notification {
        val text = when (kind) {
            ContactArrival.Kind.REQUEST -> R.string.push_contact_request_text_unknown
            ContactArrival.Kind.CONTACT -> R.string.push_contact_accepted_text_unknown
        }

        val title = when (kind) {
            ContactArrival.Kind.REQUEST -> R.string.push_contact_request_title
            ContactArrival.Kind.CONTACT -> R.string.push_contact_accepted_title
        }

        return NotificationCompat.Builder(context, CONTACTS_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(title))
            .setContentText(context.getString(text))
            .setContentIntent(
                contentIntent(
                    requestUuid = UNNAMED_REQUEST_NOTIFICATION,
                    openRequests = kind == ContactArrival.Kind.REQUEST,
                ),
            )
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            // It carries no name, so there is nothing here to hide.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
    }

    private fun tagOf(arrival: ContactArrival): String = when (arrival.kind) {
        ContactArrival.Kind.REQUEST -> REQUEST_TAG
        ContactArrival.Kind.CONTACT -> ACCEPTED_TAG
    }

    /**
     * The notification the system shows while an expedited sync runs.
     *
     * Only ever seen below Android 12, where expedited work is a foreground service
     * and a foreground service must show something.
     */
    fun syncForegroundInfo(): ForegroundInfo {
        createChannel(
            id = SYNC_CHANNEL,
            name = R.string.push_channel_sync_name,
            description = R.string.push_channel_sync_description,
            importance = NotificationManager.IMPORTANCE_MIN,
        )

        val notification = NotificationCompat.Builder(context, SYNC_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.push_sync_notification))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

        return ForegroundInfo(SYNC_NOTIFICATION, notification)
    }

    /**
     * Posts one notification, if the user has left the app able to.
     *
     * The check sits against the call rather than at the top of the callers: it is
     * the form the platform's own tooling recognizes, and the permission can be taken
     * away between one notification and the next.
     */
    private fun post(tag: String, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        NotificationManagerCompat.from(context).notify(tag, id, notification)

        posted.record(tag = tag, id = id)
    }

    private fun createChannel(id: String, name: Int, description: Int, importance: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(id, context.getString(name), importance).apply {
            this.description = context.getString(description)
        }

        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    private fun titleOf(arrival: ContactArrival): Int = when (arrival.kind) {
        ContactArrival.Kind.REQUEST -> R.string.push_contact_request_title
        ContactArrival.Kind.CONTACT -> R.string.push_contact_accepted_title
    }

    /** The wording without a name, for a profile this device could not read yet. */
    private fun textOf(arrival: ContactArrival): Int = when (arrival.kind) {
        ContactArrival.Kind.REQUEST -> when (arrival.fullName) {
            null -> R.string.push_contact_request_text_unknown
            else -> R.string.push_contact_request_text
        }

        ContactArrival.Kind.CONTACT -> when (arrival.fullName) {
            null -> R.string.push_contact_accepted_text_unknown
            else -> R.string.push_contact_accepted_text
        }
    }

    /**
     * Opens the app where the news is: the request list for something to answer, the
     * contact list for somebody who is already a contact.
     */
    private fun contentIntent(
        requestUuid: Int,
        openRequests: Boolean = true,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK

            if (openRequests) putExtra(EXTRA_OPEN_CONTACT_REQUESTS, true)
        }

        return PendingIntent.getActivity(
            context,
            requestUuid,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
