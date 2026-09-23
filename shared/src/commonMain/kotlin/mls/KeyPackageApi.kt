package mls

import networking.DuckCountClient
import networking.DuckCountRoutes
import util.NetworkError
import util.Result

/**
 * Remote data source for the caller's MLS key packages, which is what lets a peer start
 * a group with them while they are offline.
 */
class KeyPackageApi(
    private val client: DuckCountClient,
) {
    /**
     * Publishes the caller's key packages, replacing every one the server held for them.
     *
     * [userUuid] must be the authenticated user's, the server rejects anything else with a
     * `403`. More than 100 one-time key packages is a `400`.
     */
    suspend fun publishKeyPackages(
        userUuid: String,
        body: KeyPackagesPublicationDto,
    ): Result<Unit, NetworkError> =
        client.put<Unit, KeyPackagesPublicationDto>(
            route = DuckCountRoutes.User.KEY_PACKAGE,
            pathParams = mapOf("user_uuid" to userUuid),
            body = body,
        )

    /**
     * Takes one of [userUuid]'s key packages, which is what a group they are to be invited
     * into is built from.
     *
     * Any authenticated caller may ask for any user's. A `404` means the user has never
     * published any, or no longer exists; the two are the same answer here, nobody can be
     * invited either way.
     */
    suspend fun consumeKeyPackage(
        userUuid: String,
    ): Result<ConsumeKeyPackageDto, NetworkError> =
        client.get<ConsumeKeyPackageDto>(
            route = DuckCountRoutes.User.KEY_PACKAGE,
            pathParams = mapOf("user_uuid" to userUuid),
        )
}
