package database.user

data class User(
    val uuid: String,
    val username: String,
    val fullName: String,
    // TODO: val profilePicture: String,
    val isDeleted: Boolean = false,
)
