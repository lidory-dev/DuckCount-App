package database.domain

data class User(
    val uuid: String,
    val username: String, // TODO: Remove.
    val fullName: String,
    // TODO: val profilePicture: String,
    val isDeleted: Boolean = false,
)
