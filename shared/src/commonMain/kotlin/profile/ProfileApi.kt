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
 * The user profiles the server keeps. [ProfileApi] is the one there is.
 *
 * Its own interface so that what [ProfileRepository] does with them, and in what order, can
 * be checked without a server.
 */
interface ProfileStore {

    /** See [ProfileApi.getUserProfile]. */
    suspend fun getUserProfile(
        userUuid: String,
        encryptionKeyPartialHash: String,
    ): Result<ServedUserProfileDto, NetworkError>

    /** See [ProfileApi.saveUserProfile]. */
    suspend fun saveUserProfile(
        userUuid: String,
        encryptionKeyPartialHash: String,
        body: UserProfileDto,
    ): Result<Unit, NetworkError>
}

/**
 * Remote data source for user profiles.
 *
 * Every field it moves is sealed on the device under the owner's profile encryption
 * key, so the server stores and returns a profile it cannot read.
 *
 * The server keeps one version of a profile per key it was sealed under, the newest ten of
 * them, so that whoever holds a key the owner has since replaced still reads the profile as
 * it stood when that key was replaced, and nothing written after: the owner replaces it when
 * they remove a contact, see [ProfileRepository.rotateProfileKey]. What they read says that it
 * is not the latest, and no more, which is how they learn to ask for the newer key.
 *
 * Unlike the rest of `/users`, reading is not restricted to the caller's own row,
 * any logged-in user may fetch any profile, provided they can prove possession of
 * the key. That is what lets a contact read the profile shared with them. Writing
 * is the caller's own row only, and the server rejects anything else with a 403.
 */
class ProfileApi(
    private val client: DuckCountClient,
) : ProfileStore {
    /**
     * Fetches the version of a profile sealed under the key whose partial hash is
     * [encryptionKeyPartialHash], proving possession of it, and whether it is the owner's latest,
     * see [ServedUserProfileDto.isLatest].
     *
     * A key that does not match, a version the server has pruned since and a user with no
     * profile are all answered with a 404, deliberately indistinguishable.
     */
    override suspend fun getUserProfile(
        userUuid: String,
        encryptionKeyPartialHash: String,
    ): Result<ServedUserProfileDto, NetworkError> =
        client.get<ServedUserProfileDto>(
            route = DuckCountRoutes.User.PROFILE,
            pathParams = mapOf("user_uuid" to userUuid),
            headers = mapOf(
                PROFILE_ENCRYPTION_KEY_PARTIAL_HASH_HEADER to encryptionKeyPartialHash,
            ),
        )

    /**
     * Uploads the caller's own profile, sealed under the key whose partial hash is
     * [encryptionKeyPartialHash], which later reads of this version are gated on.
     *
     * Under the hash of the current key, it rewrites the current version. Under a new one, it
     * adds a version and leaves the older ones as they were, the server pruning the oldest
     * beyond the newest ten.
     */
    override suspend fun saveUserProfile(
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
