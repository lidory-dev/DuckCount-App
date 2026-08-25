package app.lidory.duckcount

enum class UsernameError {
    EMPTY,
    MUST_START_WITH_LETTER,
    INVALID_CHARACTER,
    TOO_LONG,
}
