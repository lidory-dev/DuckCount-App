package database.domain

/** A contact request waiting for the user's answer. */
data class ContactRequest(
    val uuid: String,
    val senderUserUuid: String,

    /** The name on the sender's profile, null until it could be read. */
    val senderFullName: String?,
)
