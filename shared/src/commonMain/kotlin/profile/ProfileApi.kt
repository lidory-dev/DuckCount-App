package profile

import networking.DuckCountClient
import networking.DuckCountRoutes
import util.NetworkError
import util.Result

/**
 * Header carrying a prefix of the hash of the profile encryption key.
 *
 * The bearer token proves the caller is a logged-in user, and this proves they hold
 * the key the profile owner sealed their profile with, without the key itself ever
 * being transmitted. `lib-duckcount`'s `profileEncryptionKeyPartialHash` is the only
 * thing that should ever produce it.
 */
private const val PROFILE_ENCRYPTION_KEY_PARTIAL_HASH_HEADER =
    "X-Profile-Encryption-Key-Partial-Hash"

/**
 * Remote data source for user profiles.
 *
 * Every field it moves is sealed on the device under the owner's profile encryption
 * key, so the server stores and returns a profile it cannot read.
 *
 * Unlike the rest of `/users`, reading is not restricted to the caller's own row,
 * any logged-in user may fetch any profile, provided they can prove possession of
 * the key. That is what lets a contact read the profile shared with them. Writing
 * is the caller's own row only, and the server rejects anything else with a 403.
 */
class ProfileApi(
    private val client: DuckCountClient,
) {
    /**
     * Fetches a profile, proving possession of the key it was sealed with.
     *
     * A key that does not match and a user with no profile are both answered with a
     * 404, deliberately indistinguishable.
     */
    suspend fun getUserProfile(
        userUuid: String,
        encryptionKeyPartialHash: String,
    ): Result<UserProfileDto, NetworkError> =
        client.get<UserProfileDto>(
            route = DuckCountRoutes.User.PROFILE,
            pathParams = mapOf("user_uuid" to userUuid),
            headers = mapOf(
                PROFILE_ENCRYPTION_KEY_PARTIAL_HASH_HEADER to encryptionKeyPartialHash,
            ),
        )

    /**
     * Uploads the caller's own profile, replacing any profile already stored.
     *
     * The partial hash is stored alongside it and becomes what later reads are gated
     * on.
     */
    suspend fun saveUserProfile(
        userUuid: String,
        encryptionKeyPartialHash: String,
        body: UserProfileDto,
    ): Result<Unit, NetworkError> =
        client.put<Unit, UserProfileDto>(
            route = DuckCountRoutes.User.PROFILE,
            pathParams = mapOf("user_uuid" to userUuid),
            headers = mapOf(
                PROFILE_ENCRYPTION_KEY_PARTIAL_HASH_HEADER to encryptionKeyPartialHash,
            ),
            body = body,
        )
}
