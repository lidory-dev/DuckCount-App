package model

data class User(
    val uuid: String,
    val username: String,
    val fullName: String,
    // TODO: use profile real picture
    val profilePicture: String? = null,
)
