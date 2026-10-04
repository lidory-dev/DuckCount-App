package app.lidory.duckcount.push

import android.content.Context

/**
 * Whether a contentless stand-in is on screen, as this app's own record of what it posted.
 *
 * A stand-in is what a locked device puts up from the signal alone, and the named
 * notification that follows has to replace it rather than appear beside it: they are the
 * same arrival, and the user should be told about it once.
 *
 * Asking the system would be the obvious way to know, and does not work.
 * `NotificationManagerCompat.getActiveNotifications()` answers with an empty list on some
 * builds even while the app's notifications are plainly in the shade, observed on a Pixel
 * 7 Pro on Android 16, where `dumpsys notification` listed two of this app's own and the
 * API returned none. Getting that answer wrong is not cosmetic: it shows one expense twice,
 * once namelessly and once named.
 *
 * So the app writes down what it posted. The two sides run in different processes often
 * enough (the push service posts the stand-in, a worker posts the named one) that this has
 * to outlive a process, hence preferences rather than a field. They are ordinary
 * preferences, not the vault: a locked device can read them, which is the whole point.
 */
internal class StandIns(context: Context) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Records that the stand-in for [kind] is on screen, and counts it, see [count]. */
    fun markUp(kind: Kind) {
        preferences.edit()
            .putBoolean(kind.key, true)
            .putLong(kind.countKey, count(kind = kind) + 1)
            .apply()
    }

    /** Whether the stand-in for [kind] is on screen, leaving the record as it is. */
    fun isUp(kind: Kind): Boolean = preferences.getBoolean(kind.key, false)

    /**
     * How many times a stand-in for [kind] has gone up, which only grows until [clear].
     *
     * They all go up under one id, so whether one is up cannot say which. Read before a
     * sync and again after it, this says whether one went up in between, for a message the
     * sync may already have gone past.
     */
    fun count(kind: Kind): Long = preferences.getLong(kind.countKey, 0L)

    /**
     * Whether the stand-in for [kind] was on screen, clearing the record either way.
     *
     * The caller is about to replace it, so by the time this returns it is not up any more
     * whatever the answer was.
     */
    fun takeDown(kind: Kind): Boolean {
        val wasUp = preferences.getBoolean(kind.key, false)

        preferences.edit().remove(kind.key).apply()

        return wasUp
    }

    /**
     * Forgets every stand-in, as the session that put them up ends.
     *
     * Written before this returns rather than queued, since the process is usually killed
     * right after, see [authenticationUtils.SessionLeftovers.clear].
     */
    fun clear() {
        preferences.edit().clear().commit()
    }

    /** What a stand-in can stand for. */
    enum class Kind(val key: String) {
        CONTACT_REQUEST("contact-request"),
        ONE_TIME_EXPENSE("one-time-expense");

        val countKey: String get() = "$key-count"
    }

    private companion object {
        const val PREFERENCES = "push-stand-ins"
    }
}
