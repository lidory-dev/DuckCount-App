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

    /** Records that the stand-in for [kind] is on screen. */
    fun markUp(kind: Kind) {
        preferences.edit().putBoolean(kind.key, true).apply()
    }

    /** Whether the stand-in for [kind] is on screen, leaving the record as it is. */
    fun isUp(kind: Kind): Boolean = preferences.getBoolean(kind.key, false)

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

    /** What a stand-in can stand for. */
    enum class Kind(val key: String) {
        CONTACT_REQUEST("contact-request"),
        ONE_TIME_EXPENSE("one-time-expense"),
    }

    private companion object {
        const val PREFERENCES = "push-stand-ins"
    }
}
