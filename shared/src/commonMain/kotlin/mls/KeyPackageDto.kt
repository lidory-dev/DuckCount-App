package mls

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Body of `PUT /users/{user_uuid}/key-package`, every key package Base64 encoded.
 *
 * The server stores them without reading them, and the publication replaces every key
 * package it held for the user before.
 */
@Serializable
data class KeyPackagesPublicationDto(
    /** Handed out to peers only once the one-time pool is empty, and never consumed. */
    @SerialName("last_resort_key_package")
    val lastResortKeyPackage: String,

    /** Each handed out to a single peer, then deleted. */
    @SerialName("one_time_key_packages")
    val oneTimeKeyPackages: List<String>,
)

/**
 * Response of `GET /users/{user_uuid}/key-package`, the one key package the server handed
 * out for that user, Base64 encoded.
 *
 * Reading it spends it: the server deletes the one-time key package it returns, and only
 * falls back to the user's never-deleted last-resort one once the pool is empty. So a
 * caller asks for one when it is about to build a group, not to find out whether it could.
 */
@Serializable
data class ConsumeKeyPackageDto(
    @SerialName("key_package")
    val keyPackage: String,
)
