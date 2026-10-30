package app.lidory.duckcount.push

import android.content.Context

/**
 * Which notifications this app put up under a tag, as its own record of them.
 *
 * Taking notifications down means knowing their ids, and the system cannot be asked which
 * ones are up, for the reason [StandIns] gives. So the app writes down what it posts. Kept
 * in preferences for the same reason too: what a worker posts has to be taken down by a
 * screen long after that process may have died.
 */
internal class PostedNotifications(context: Context) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Records that notification [id] went up under [tag]. */
    @Synchronized
    fun record(tag: String, id: Int) {
        val ids = preferences.getStringSet(tag, emptySet()).orEmpty()

        preferences.edit().putStringSet(tag, ids + id.toString()).apply()
    }

    /**
     * The ids recorded under [tag], forgetting them.
     *
     * The caller is about to take them down, or records again those it leaves up. One the user
     * already dismissed is among them, and taking it down again does nothing.
     */
    @Synchronized
    fun takeAll(tag: String): Set<Int> {
        val ids = preferences.getStringSet(tag, emptySet()).orEmpty()

        preferences.edit().remove(tag).apply()

        return ids.mapTo(mutableSetOf()) { it.toInt() }
    }

    /**
     * Forgets every notification recorded, as the session that put them up ends.
     *
     * Written before this returns rather than queued, since the process is usually killed
     * right after, see [authenticationUtils.SessionLeftovers.clear].
     */
    @Synchronized
    fun clear() {
        preferences.edit().clear().commit()
    }

    private companion object {
        const val PREFERENCES = "push-posted"
    }
}
