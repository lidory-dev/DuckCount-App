package util

/**
 * Spends one message of a peer's budget, returning when the budget will be whole again, or
 * null when the message cannot go out now.
 *
 * Every message the user sends counts against the one allowance the server gives them, and
 * some of those messages a peer decides the number of: the replies to what they send, see
 * `channel.DirectChannelRepository.claimReply`, and the requests for the keys to expenses of
 * theirs, see `expense.OneTimeExpenseSync.requestMissingKeys`. Each kind gets a budget per
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
