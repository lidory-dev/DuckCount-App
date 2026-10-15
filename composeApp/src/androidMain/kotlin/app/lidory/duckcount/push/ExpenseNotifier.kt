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
import app.lidory.duckcount.MainActivity
import app.lidory.duckcount.R
import expense.ExpenseArrival
import expense.ExpenseChange
import push.PushSignal

/**
 * The channel the user sees, and can turn off on its own.
 *
 * Separate from the contacts one so that muting expenses does not also silence somebody
 * asking to be a contact: those arrive at very different rates, and a user who tires of one
 * should not lose the other.
 */
private const val EXPENSES_CHANNEL = "expenses"

/** What these notifications are about, so a screen showing them can take them all down. */
private const val EXPENSE_TAG = "expense-added"

/** Ties this app's expense notifications together under one heading. */
private const val EXPENSE_GROUP = "expenses"

/** The id of the notification that heads the group. Fixed: there is only ever one. */
private const val EXPENSE_SUMMARY_NOTIFICATION = 3

/**
 * The id of the notification posted from a signal alone, with nobody's name on it.
 *
 * Fixed, so a second stand-in replaces the first rather than joining it, and used for
 * nothing else: a named notification under it would be replaced by the next stand-in.
 */
private const val UNNAMED_EXPENSE_NOTIFICATION = 4

/**
 * What the notifications about a change to an expense are about.
 *
 * Apart from [EXPENSE_TAG], which a screen showing the newest expenses takes down: a change can
 * be to any expense in the list, see [ExpenseNotifier.notifyChanges].
 */
private const val EXPENSE_CHANGE_TAG = "expense-changed"

/** Ties the changes together under a heading of their own, the arrivals' counting new expenses. */
private const val EXPENSE_CHANGE_GROUP = "expense-changes"

/** The id of the notification that heads the changes' group. */
private const val EXPENSE_CHANGE_SUMMARY_NOTIFICATION = 5

/**
 * The id of the stand-in for a change, fixed and kept to it for the reasons
 * [UNNAMED_EXPENSE_NOTIFICATION] gives.
 */
private const val UNNAMED_EXPENSE_CHANGE_NOTIFICATION = 6

/**
 * How many people may be named in one sync before the rest are left to the summary.
 *
 * Somebody coming back from a log-out can become able to read months of expenses at once,
 * from everyone they know, and a notification per person would be a screenful.
 */
private const val MAX_CREDITORS_ANNOUNCED = 4

/**
 * Tells the user about expenses somebody shared with them.
 *
 * One notification per person rather than per expense: a friend entering a weekend's
 * spending in one sitting is one thing that happened, and six near-identical rows is how a
 * channel gets muted.
 *
 * What its creditor does to an expense afterwards is told as well, one notification per
 * expense: marking it as settled or not, editing it, removing it, see [notifyChanges].
 *
 * A locked device is told as much as the signal says, and no more. Nothing on a locked
 * phone can read the app's storage, so there is no finding out who shared what: the server
 * sends a signal that says an expense arrived, and that is what goes up, without a name on
 * it. The named one takes its place, in silence, once the device is unlocked and the sync
 * can read what it was.
 *
 * These ring. Somebody sharing an expense is telling the user they owe or are owed money,
 * which is the app's whole subject; the only thing that goes up quietly is a contact
 * accepting a request the user themselves sent, see `ContactNotifier`.
 *
 * No arrival goes up while the newest expenses are on screen: see [newestShown].
 */
class ExpenseNotifier(
    private val context: Context,
) {
    private val standIns = StandIns(context = context)

    private val posted = PostedNotifications(context = context)

    /**
     * Whether the list of one-time expenses is on screen, scrolled to the newest.
     *
     * Set on the main thread as the list comes, goes and scrolls, read by the worker that
     * posts.
     */
    @Volatile
    private var newestIsOnScreen = false

    /**
     * The newest expenses came on screen: takes down everything said about them, and says
     * nothing more until [newestHidden].
     *
     * Whatever those notifications said, the user is now reading. And an expense arriving
     * from here on goes in where they are looking, so a notification about it would tell
     * them twice, and ring for something they just watched happen.
     *
     * Synchronized with [notify], so that one already under way cannot put up what this has
     * just taken down.
     */
    @Synchronized
    fun newestShown() {
        newestIsOnScreen = true

        clearExpenses()
    }

    /**
     * The newest expenses left the screen, with the list or by scrolling, so an expense
     * arriving is announced again: it would go in out of sight.
     */
    @Synchronized
    fun newestHidden() {
        newestIsOnScreen = false
    }

    /**
     * Puts up one notification per person whose expenses became readable.
     *
     * Grouped rather than stacked, so several people arriving in one sync collapse under a
     * single heading the user can dismiss in one go.
     */
    @Synchronized
    fun notify(arrivals: List<ExpenseArrival>) {
        if (arrivals.isEmpty()) return

        if (newestIsOnScreen) {
            // They went in where the user is looking, and so did whatever a stand-in
            // announced: this is the moment it has nothing left to say.
            takeDownStandIn(
                kind = StandIns.Kind.ONE_TIME_EXPENSE,
                tag = EXPENSE_TAG,
                id = UNNAMED_EXPENSE_NOTIFICATION,
            )

            return
        }

        createChannel()

        var standInIsShowing = standIns.takeDown(kind = StandIns.Kind.ONE_TIME_EXPENSE)

        val byCreditor = arrivals.groupBy { it.creditorUuid }
        val announced = byCreditor.entries.take(MAX_CREDITORS_ANNOUNCED)

        announced.forEach { (creditorUuid, expenses) ->
            // The nameless one the locked device put up was about this, so the first
            // named one takes its place rather than appearing beside it.
            val takesOverStandIn = standInIsShowing

            if (takesOverStandIn) standInIsShowing = false

            post(
                tag = EXPENSE_TAG,
                // One per person, replaced rather than stacked: more expenses from somebody
                // who already has a notification up is the same news, counted higher. Never
                // under the stand-in's id, even taking its place: the next stand-in goes up
                // there, and would replace it before it was read.
                id = creditorUuid.hashCode(),
                notification = notificationFor(
                    creditorFullName = expenses.first().creditorFullName,
                    count = expenses.size,
                    // It rang as the stand-in moments ago; ringing again would be ringing
                    // twice for one expense.
                    replacesStandIn = takesOverStandIn,
                ),
            )

            // Only once the named one is up, so that there is no moment with neither.
            if (takesOverStandIn) {
                NotificationManagerCompat.from(context)
                    .cancel(EXPENSE_TAG, UNNAMED_EXPENSE_NOTIFICATION)
            }
        }

        if (byCreditor.size > 1) {
            postSummary(
                tag = EXPENSE_TAG,
                id = EXPENSE_SUMMARY_NOTIFICATION,
                group = EXPENSE_GROUP,
                title = R.string.push_expense_title,
                text = context.resources.getQuantityString(
                    R.plurals.push_expense_summary,
                    arrivals.size,
                    arrivals.size,
                ),
            )
        }
    }

    /**
     * Puts up one notification per expense its creditor changed, saying what they did.
     *
     * Per expense rather than per person, unlike [notify]: each says something about that one
     * expense, and two of somebody's expenses marked as settled are two things to know. A later
     * change to an expense whose notification is still up replaces it without ringing again:
     * the user has yet to read the first, and that expense has already rung once.
     *
     * These go up whatever is on screen, and [newestShown] leaves them up. An expense arriving
     * goes in at the top of the list, which is what the newest being on screen says the user
     * is looking at; a change is to an expense wherever it sits in the list, which that says
     * nothing about.
     */
    @Synchronized
    fun notifyChanges(changes: List<ExpenseChange>) {
        if (changes.isEmpty()) return

        createChannel()

        var standInIsShowing = standIns.takeDown(kind = StandIns.Kind.ONE_TIME_EXPENSE_CHANGED)

        changes.forEach { change ->
            // The nameless one the locked device put up was about this, so the first named
            // one takes its place rather than appearing beside it.
            val takesOverStandIn = standInIsShowing

            if (takesOverStandIn) standInIsShowing = false

            post(
                tag = EXPENSE_CHANGE_TAG,
                // One per expense, replaced rather than stacked. Never under the stand-in's id,
                // even taking its place: the next stand-in goes up there, and would replace it
                // before it was read.
                id = change.expenseUuid.hashCode(),
                notification = changeNotificationFor(
                    change = change,
                    replacesStandIn = takesOverStandIn,
                ),
            )

            // Only once the named one is up, so that there is no moment with neither.
            if (takesOverStandIn) {
                NotificationManagerCompat.from(context)
                    .cancel(EXPENSE_CHANGE_TAG, UNNAMED_EXPENSE_CHANGE_NOTIFICATION)
            }
        }

        if (changes.size > 1) {
            postSummary(
                tag = EXPENSE_CHANGE_TAG,
                id = EXPENSE_CHANGE_SUMMARY_NOTIFICATION,
                group = EXPENSE_CHANGE_GROUP,
                title = R.string.push_expense_changed_title,
                text = context.resources.getQuantityString(
                    R.plurals.push_expense_changed_summary,
                    changes.size,
                    changes.size,
                ),
            )
        }
    }

    /**
     * How many stand-ins of each kind have gone up so far, for a sync to read before it reads
     * anything else and hand back to [retireStandIns].
     */
    fun standInCounts(): StandInCounts = StandInCounts(
        arrivals = standIns.count(kind = StandIns.Kind.ONE_TIME_EXPENSE),
        changes = standIns.count(kind = StandIns.Kind.ONE_TIME_EXPENSE_CHANGED),
    )

    /**
     * Takes the stand-ins down once a sync is done, each that nothing it read took over.
     *
     * Whatever one announced is either in by then, a screen's own sync having got there
     * first, or never was: the signal is the sender's word alone, and anybody who can reach
     * the user can send one for an expense that does not exist. A creditor marking an expense
     * as what it already was is signalled too, and changes nothing. Left up, a stand-in would
     * stay up for good, and the next arrival or change to come in would take it over without
     * a sound, see [notify]. A sync that gave up may have left the expense unread, and lets
     * the stand-in go all the same: the next sync to read it announces it by name.
     *
     * Except one that went up while the sync ran, which [countsBefore], the
     * [standInCounts] read as it began, tells apart. The server signals once the message, or
     * the change, is stored, so a stand-in counted before the sync read anything is for
     * something it read, but a later one may be for something it had already gone past.
     */
    @Synchronized
    fun retireStandIns(countsBefore: StandInCounts) {
        val counts = standInCounts()

        if (counts.arrivals == countsBefore.arrivals) {
            takeDownStandIn(
                kind = StandIns.Kind.ONE_TIME_EXPENSE,
                tag = EXPENSE_TAG,
                id = UNNAMED_EXPENSE_NOTIFICATION,
            )
        }

        if (counts.changes == countsBefore.changes) {
            takeDownStandIn(
                kind = StandIns.Kind.ONE_TIME_EXPENSE_CHANGED,
                tag = EXPENSE_CHANGE_TAG,
                id = UNNAMED_EXPENSE_CHANGE_NOTIFICATION,
            )
        }
    }

    /**
     * Says what a signal said, for a device too locked to find out more.
     *
     * Only an expense signal is announced this way: an expense arriving, or one the user is
     * on changing. A plain `message` is an acceptance or a notice that somebody removed the
     * user, and saying "something happened" when it might be the second would be worse than
     * saying nothing.
     *
     * Synchronized with [notify], [notifyChanges] and [retireStandIns], so that a stand-in
     * cannot go up in the middle of any of them, and be taken down by what was meant for the
     * last one.
     */
    @Synchronized
    fun notifyWhileLocked(signal: PushSignal) {
        when (signal) {
            PushSignal.ONE_TIME_EXPENSE -> {
                createChannel()

                post(
                    tag = EXPENSE_TAG,
                    id = UNNAMED_EXPENSE_NOTIFICATION,
                    notification = namelessNotification(),
                )

                standIns.markUp(kind = StandIns.Kind.ONE_TIME_EXPENSE)
            }

            PushSignal.ONE_TIME_EXPENSE_CHANGED -> {
                createChannel()

                post(
                    tag = EXPENSE_CHANGE_TAG,
                    id = UNNAMED_EXPENSE_CHANGE_NOTIFICATION,
                    notification = namelessChangeNotification(),
                )

                standIns.markUp(kind = StandIns.Kind.ONE_TIME_EXPENSE_CHANGED)
            }

            else -> Unit
        }
    }

    /** Takes down the stand-in for [kind], posted under [tag] and [id], if it is up. */
    private fun takeDownStandIn(kind: StandIns.Kind, tag: String, id: Int) {
        if (standIns.takeDown(kind = kind)) {
            NotificationManagerCompat.from(context).cancel(tag, id)
        }
    }

    /**
     * The form that names nobody: an expense arrived, and nothing about whose or what.
     *
     * Posted on its own while the device is locked, and carried by every named one as its
     * [NotificationCompat.Builder.setPublicVersion], which is what a lockscreen shows for a
     * user who has asked to keep notification content hidden. Without it Android has
     * nothing safe to fall back on and shows the app's name alone, which tells them only
     * that DuckCount wanted something.
     */
    private fun namelessNotification(): Notification =
        NotificationCompat.Builder(context, EXPENSES_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.push_expense_title))
            .setContentText(context.getString(R.string.push_expense_text_locked))
            .setContentIntent(contentIntent(requestUuid = UNNAMED_EXPENSE_NOTIFICATION))
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setGroup(EXPENSE_GROUP)
            // It carries no name and no amount, so there is nothing here to hide.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()

    /**
     * The form of a change that names nobody: an expense shared with the user was updated, and
     * nothing about which, by whom or how.
     *
     * Posted on its own while the device is locked, and carried by every named one as its
     * public version, for the same reasons as [namelessNotification].
     */
    private fun namelessChangeNotification(): Notification =
        NotificationCompat.Builder(context, EXPENSES_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.push_expense_changed_title))
            .setContentText(context.getString(R.string.push_expense_changed_text_locked))
            .setContentIntent(contentIntent(requestUuid = UNNAMED_EXPENSE_CHANGE_NOTIFICATION))
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setGroup(EXPENSE_CHANGE_GROUP)
            // It carries no name and no title, so there is nothing here to hide.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()

    /**
     * Takes down every notification about an expense that arrived, except a stand-in.
     *
     * What was said about a change is left: it can be about any expense in the list, not only
     * the newest ones on screen.
     *
     * What a stand-in announced is not in the list yet: the sync that reads it waits for the
     * unlock that also brought the user here. Taken down now, the named notification that
     * sync puts up if the user has moved on would find nothing to replace, and would ring a
     * second time for the same expense. [notify] takes it over once its expense is in, and
     * [retireStandIns] takes it down when the sync ends without one.
     */
    private fun clearExpenses() {
        val manager = NotificationManagerCompat.from(context)

        val spared = UNNAMED_EXPENSE_NOTIFICATION.takeIf {
            standIns.isUp(kind = StandIns.Kind.ONE_TIME_EXPENSE)
        }

        posted.takeAll(tag = EXPENSE_TAG)
            .filter { it != spared }
            .forEach { manager.cancel(EXPENSE_TAG, it) }
    }

    private fun notificationFor(
        creditorFullName: String?,
        count: Int,
        replacesStandIn: Boolean,
    ): Notification {
        val text = when {
            creditorFullName == null -> context.resources.getQuantityString(
                R.plurals.push_expense_text_unknown,
                count,
                count,
            )

            else -> context.resources.getQuantityString(
                R.plurals.push_expense_text,
                count,
                creditorFullName,
                count,
            )
        }

        return NotificationCompat.Builder(context, EXPENSES_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.push_expense_title))
            .setContentText(text)
            .setContentIntent(contentIntent(requestUuid = creditorFullName.hashCode()))
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setGroup(EXPENSE_GROUP)
            // Who shared what is the user's business and nobody else's, so a lockscreen set
            // to hide notification content shows the nameless form instead of this one.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(namelessNotification())
            // Silent only in a stand-in's place, which `setOnlyAlertOnce` cannot say: it
            // quiets an update to a notification still up under the same id, and this goes
            // up under its own. Deliberately not otherwise: this replaces the notification
            // already up for that person, with a higher count, and more expenses from them
            // is more news rather than the same news. Suppressing it would also mean that
            // one left sitting in the shade kept every later expense from them quiet.
            .setSilent(replacesStandIn)
            .setAutoCancel(true)
            .build()
    }

    private fun changeNotificationFor(
        change: ExpenseChange,
        replacesStandIn: Boolean,
    ): Notification {
        val text = when (val creditorFullName = change.creditorFullName) {
            null -> context.getString(unnamedTextOf(kind = change.kind), change.title)
            else -> context.getString(textOf(kind = change.kind), creditorFullName, change.title)
        }

        return NotificationCompat.Builder(context, EXPENSES_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.push_expense_changed_title))
            .setContentText(text)
            .setContentIntent(contentIntent(requestUuid = change.expenseUuid.hashCode()))
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setGroup(EXPENSE_CHANGE_GROUP)
            // Who changed what is the user's business and nobody else's, so a lockscreen set
            // to hide notification content shows the nameless form instead of this one.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(namelessChangeNotification())
            // Rings once per expense: another change to it while this is still up is the same
            // expense to look at, and goes up in its place without a sound.
            .setOnlyAlertOnce(true)
            // Silent in a stand-in's place, which `setOnlyAlertOnce` cannot say, this going up
            // under an id of its own: the stand-in rang for it moments ago.
            .setSilent(replacesStandIn)
            .setAutoCancel(true)
            .build()
    }

    /** What the creditor did, naming them. */
    private fun textOf(kind: ExpenseChange.Kind): Int = when (kind) {
        ExpenseChange.Kind.REMOVED -> R.string.push_expense_removed
        ExpenseChange.Kind.EDITED -> R.string.push_expense_edited
        ExpenseChange.Kind.MARKED_SETTLED -> R.string.push_expense_marked_settled
        ExpenseChange.Kind.MARKED_NOT_SETTLED -> R.string.push_expense_marked_not_settled
        ExpenseChange.Kind.EDITED_AND_MARKED_SETTLED -> R.string.push_expense_edited_marked_settled
        ExpenseChange.Kind.EDITED_AND_MARKED_NOT_SETTLED ->
            R.string.push_expense_edited_marked_not_settled
    }

    /** The same without a name, for a creditor this device holds none for. */
    private fun unnamedTextOf(kind: ExpenseChange.Kind): Int = when (kind) {
        ExpenseChange.Kind.REMOVED -> R.string.push_expense_removed_unknown
        ExpenseChange.Kind.EDITED -> R.string.push_expense_edited_unknown
        ExpenseChange.Kind.MARKED_SETTLED -> R.string.push_expense_marked_settled_unknown
        ExpenseChange.Kind.MARKED_NOT_SETTLED -> R.string.push_expense_marked_not_settled_unknown
        ExpenseChange.Kind.EDITED_AND_MARKED_SETTLED ->
            R.string.push_expense_edited_marked_settled_unknown
        ExpenseChange.Kind.EDITED_AND_MARKED_NOT_SETTLED ->
            R.string.push_expense_edited_marked_not_settled_unknown
    }

    /** Puts up the heading of [group], under [title], saying [text]. */
    private fun postSummary(tag: String, id: Int, group: String, title: Int, text: String) {
        val notification = NotificationCompat.Builder(context, EXPENSES_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(title))
            .setContentText(text)
            .setContentIntent(contentIntent(requestUuid = id))
            .setGroup(group)
            .setGroupSummary(true)
            // The summary is a heading over the ones that carry the news, so it is the
            // children that ring. Left to itself it would ring too, which for one sync
            // means being told twice.
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .build()

        post(tag = tag, id = id, notification = notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            EXPENSES_CHANNEL,
            context.getString(R.string.push_channel_expenses_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.push_channel_expenses_description)
        }

        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    /**
     * Opens the app, which lands on the expense list.
     *
     * No extra to route on: unlike a contact request, there is nothing here to answer, and
     * the expense is in the list the app opens on anyway.
     */
    private fun contentIntent(requestUuid: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }

        return PendingIntent.getActivity(
            context,
            requestUuid,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Posts one notification, if the user has left the app able to. */
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

        // Only the arrivals are taken back down from the record, see [clearExpenses]: a change
        // stays up whatever is on screen, so nothing would ever read its ids.
        if (tag == EXPENSE_TAG) posted.record(tag = tag, id = id)
    }
}

/**
 * How many stand-ins of each kind had gone up at one moment, to compare a later one against,
 * see [ExpenseNotifier.retireStandIns].
 */
class StandInCounts internal constructor(
    internal val arrivals: Long,
    internal val changes: Long,
)
