package profile

import database.data.repository.UserRepository
import database.domain.User
import uniffi.lib_duckcount.profileEncryptionKeyPartialHash
import util.EmptyResult
import util.NetworkError
import util.Result
import vault.VaultRepository
import vault.sealBound
import vault.unsealBound

/** Sealed in place of a field the app has no screen for yet. */
private val UNSET_FIELD = ByteArray(0)

/**
 * What each sealed field of a profile is bound to.
 *
 * Every field of one profile is sealed under the same key, so without this they would all
 * be interchangeable: the server could serve the empty phone number where the name was,
 * and it would decrypt perfectly into a blank name, or later the bank details into it.
 * The key already binds the owner; naming the field binds the rest.
 */
private object Context {
    const val FULL_NAME = "profile:full_name"
    const val PHONE_NUMBER = "profile:phone_number"
    const val BANK_DETAILS = "profile:bank_details"
}

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
            fullName = sealBound(
                key = key,
                context = Context.FULL_NAME,
                plaintext = fullName.encodeToByteArray(),
            ) ?: return Result.Error(ProfileError.Crypto),
            phoneNumber = sealBound(key = key, context = Context.PHONE_NUMBER, plaintext = UNSET_FIELD)
                ?: return Result.Error(ProfileError.Crypto),
            bankDetails = sealBound(key = key, context = Context.BANK_DETAILS, plaintext = UNSET_FIELD)
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

        val fullName = unsealBound(
            key = profileEncryptionKey,
            context = Context.FULL_NAME,
            content = profile.fullName,
        )?.decodeToString() ?: return Result.Error(ProfileError.Crypto)

        return Result.Success(fullName)
    }
}
