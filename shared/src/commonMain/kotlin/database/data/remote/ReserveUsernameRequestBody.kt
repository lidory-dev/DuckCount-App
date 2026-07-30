package database.data.remote

import kotlinx.serialization.Serializable

/**
 * Request body for `PUT /users/{user_uuid}/username`.
 *
 * [username] is the digest of the handle the user wants to be discoverable by, never
 * the handle itself. The server only checks its shape (128 lowercase hexadecimal
 * characters), the hashing happens client-side in `lib-duckcount`.
 */
@Serializable
data class ReserveUsernameRequestBody(
    val username: String,
)
