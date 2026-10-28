package app.lidory.duckcount.groupList.model

/**
 * Where the user stands with a member of one of their groups, as far as the two of them being
 * contacts goes, which their sheet offers to act on, see
 * [app.lidory.duckcount.groupList.viewmodel.memberSheetActions]. The composeApp callback impl
 * maps the shared relation onto this.
 *
 * Each holds only when none listed before it does: a request either way stops counting once they
 * are contacts, and one from them is what the user can act on, whatever request the user has out
 * to them.
 */
enum class MemberRelation {
    /** One of the user's contacts, blocked ones included: they still have a profile to show. */
    CONTACT,

    /** They sent the user a request, which waits for the user's answer where the contact requests are listed. */
    REQUEST_RECEIVED,

    /**
     * The user sent them a request the server is known to hold: it goes once they answer it,
     * never when they refuse it, which nothing tells this device.
     */
    REQUEST_SENT,

    /** None of the above: somebody the user can send a request to. */
    NONE,
}
