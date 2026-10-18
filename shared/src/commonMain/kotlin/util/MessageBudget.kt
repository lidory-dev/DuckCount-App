package util

/**
 * Spends one message of a peer's budget, returning when the budget will be whole again, or
 * null when the message cannot go out now.
 *
 * Every message the user sends counts against the one allowance the server gives them, and
 * some of those messages a peer decides the number of: the replies to what they send, see
 * `channel.DirectChannelRepository.claimReply`, the requests for the keys to expenses of
 * theirs, see `expense.OneTimeExpenseSync.requestMissingKeys`, and the requests for the key to
 * their profile, see `contact.ProfileKeyPull.requestMissingKeys`. Each kind gets a budget per
 * peer instead: two messages at once and one more an hour after that, so at most 25 in a day,
 * whatever the peer does.
 *
 * The budget is kept as the one moment it will be whole again, [budgetFullAt]: each message
 * pushes that moment an hour further out, from now or from wherever it already stood. A
 * message goes out while at most one is outstanding or, [onlyWhenWhole], while none is.
 */
internal fun spendMessageBudget(budgetFullAt: Long?, now: Long, onlyWhenWhole: Boolean): Long? {
    // A moment already past is a budget already whole. So is one further out than messages can
    // push it, the clock having been set back since: read as it stands, it would hold every
    // message back until the clock caught up.
    val from = budgetFullAt?.takeIf { it in now..now + BUDGET_BURST * BUDGET_INTERVAL_MILLISECONDS } ?: now

    val allowedOutstanding = if (onlyWhenWhole) 0 else BUDGET_BURST - 1

    if (from - now > allowedOutstanding * BUDGET_INTERVAL_MILLISECONDS) return null

    return from + BUDGET_INTERVAL_MILLISECONDS
}

/** How many messages a peer's budget holds at once. */
private const val BUDGET_BURST = 2

/** How long one message takes to come back to a peer's budget. */
private const val BUDGET_INTERVAL_MILLISECONDS = 60L * 60 * 1000

/**
 * How long to wait before asking a peer for the same key again, doubling, up to a day: a
 * creditor for the key to an expense, see `expense.OneTimeExpenseSync.requestMissingKeys`, or a
 * contact for the key to their profile, see `contact.ProfileKeyPull.requestMissingKeys`.
 *
 * The peer answers an asker once a pass at most, out of a budget of two answers at once
 * and one more an hour after that, see `channel.DirectChannelRepository.claimReply`, and this
 * device asks a peer out of a budget of the same shape, see [spendMessageBudget].
 * So, each try read as it arrives and nothing else asked of the peer in between, an
 * answer lost on the way when nothing else was outstanding is answered again at the try ten
 * minutes after the first, and a second lost straight after it at the first sync an hour
 * after the first: the try at thirty is held back until then, as the peer would hold
 * back its answer. Whatever came before, a try an hour after both this device's last request
 * and the peer's last reply goes out and is answered.
 */
internal fun backoffFor(requestCount: Int): Long {
    val doublings = minOf(requestCount, MAX_BACKOFF_DOUBLINGS)

    return minOf(BASE_BACKOFF_MILLISECONDS shl doublings, MAX_BACKOFF_MILLISECONDS)
}

private const val BASE_BACKOFF_MILLISECONDS = 5L * 60 * 1000
private const val MAX_BACKOFF_MILLISECONDS = 24L * 60 * 60 * 1000
private const val MAX_BACKOFF_DOUBLINGS = 10
