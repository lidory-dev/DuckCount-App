package app.lidory.duckcount

enum class ExpenseError {
    SHARES_BELOW_TOTAL_AMOUNT,
    SHARES_EXCEED_TOTAL_AMOUNT,
    NON_POSITIVE_SHARE,
    OTHER_KNOWN_ERROR,
}