package profile

import database.data.repository.UserRepository
import database.domain.User
import uniffi.lib_duckcount.profileEncryptionKeyPartialHash
import util.EmptyResult
import util.NetworkError
import util.Result
import vault.VaultRepository
import vault.seal
import vault.unseal

/** Sealed in place of a field the app has no screen for yet. */
private val UNSET_FIELD = ByteArray(0)

/**
 * User profiles as the server holds them: the authenticated user's own, and the ones their
 * contacts shared with them.
 *
 * The key to the user's own comes from the vault, and is what a contact is handed to read
 * this profile.
 */
class ProfileRepository(
    private val profileApi: ProfileApi,
    private val vaultRepository: VaultRepository,
    private val userRepository: UserRepository,
) {

    /**
     * Uploads the user profile on the server. Seals the fields before storing them on
     * the server, and only then it writes the user profile to the local database.
     */
    suspend fun uploadProfile(
        userUuid: String,
        username: String,
        fullName: String,
    ): EmptyResult<ProfileError> {
        val key = when (val outcome = vaultRepository.getOrCreateProfileEncryptionKey()) {
            is Result.Success -> outcome.data
            is Result.Error -> return Result.Error(ProfileError.Vault(outcome.error))
        }

        val profile = UserProfileDto(
            fullName = seal(key = key, plaintext = fullName.encodeToByteArray())
                ?: return Result.Error(ProfileError.Crypto),
            phoneNumber = seal(key = key, plaintext = UNSET_FIELD)
                ?: return Result.Error(ProfileError.Crypto),
            bankDetails = seal(key = key, plaintext = UNSET_FIELD)
                ?: return Result.Error(ProfileError.Crypto),
        )

        val save = profileApi.saveUserProfile(
            userUuid = userUuid,
            encryptionKeyPartialHash = profileEncryptionKeyPartialHash(key),
            body = profile,
        )

        if (save is Result.Error) return Result.Error(ProfileError.Network(save.error))

        userRepository.insert(
            User(
                uuid = userUuid,
                username = username,
                fullName = fullName,
            ),
        )

        return Result.Success(Unit)
    }

    /**
     * Fetches the user's profile and writes it into the local database.
     */
    suspend fun restoreProfile(userUuid: String, username: String): EmptyResult<ProfileError> {
        val key = when (val outcome = vaultRepository.getOrCreateProfileEncryptionKey()) {
            is Result.Success -> outcome.data
            is Result.Error -> return Result.Error(ProfileError.Vault(outcome.error))
        }

        val fullName = when (val read = getFullName(userUuid = userUuid, profileEncryptionKey = key)) {
            is Result.Success -> read.data
            is Result.Error -> return Result.Error(read.error)
        }

        userRepository.insert(
            User(
                uuid = userUuid,
                username = username,
                fullName = fullName,
            ),
        )

        return Result.Success(Unit)
    }

    /**
     * The full name on the profile of [userUuid], which [profileEncryptionKey] seals.
     *
     * Reads anybody's profile, the user's own or a contact's, as long as the key is the
     * one its owner sealed it with.
     */
    suspend fun getFullName(
        userUuid: String,
        profileEncryptionKey: ByteArray,
    ): Result<String, ProfileError> {
        val profile = when (
            val fetch = profileApi.getUserProfile(
                userUuid = userUuid,
                encryptionKeyPartialHash = profileEncryptionKeyPartialHash(profileEncryptionKey),
            )
        ) {
            is Result.Success -> fetch.data

            is Result.Error -> return Result.Error(
                when (fetch.error) {
                    NetworkError.NOT_FOUND -> ProfileError.NotStored
                    else -> ProfileError.Network(fetch.error)
                },
            )
        }

        val fullName = unseal(key = profileEncryptionKey, content = profile.fullName)
            ?.decodeToString()
            ?: return Result.Error(ProfileError.Crypto)

        return Result.Success(fullName)
    }
}
