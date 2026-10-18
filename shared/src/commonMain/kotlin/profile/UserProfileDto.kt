package profile

import database.data.remote.EncryptedFieldDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors the server's `UserProfile`: the three fields it holds for a user, each
 * sealed under that user's profile encryption key and opaque to it. What the owner
 * uploads, see [ProfileStore.saveUserProfile].
 *
 * All three are required by the server even though the app only collects a name so
 * far, so the two it has no screen for carry a sealed empty string rather than being
 * omitted. Sealing it keeps every field one the owner's key opens. It does not hide that
 * the field is empty: AES-GCM adds no padding, so an empty field seals to its 16-byte tag
 * alone, shorter than any filled one.
 */
@Serializable
data class UserProfileDto(
    @SerialName("full_name")
    val fullName: EncryptedFieldDto,

    @SerialName("phone_number")
    val phoneNumber: EncryptedFieldDto,

    @SerialName("bank_details")
    val bankDetails: EncryptedFieldDto,
)

/**
 * Mirrors the server's `ServedUserProfile`: a version of a profile as whoever holds the key it
 * is sealed under reads it, see [ProfileStore.getUserProfile], the same three fields as
 * [UserProfileDto] and whether it is the owner's latest.
 */
@Serializable
data class ServedUserProfileDto(
    @SerialName("full_name")
    val fullName: EncryptedFieldDto,

    @SerialName("phone_number")
    val phoneNumber: EncryptedFieldDto,

    @SerialName("bank_details")
    val bankDetails: EncryptedFieldDto,

    /**
     * Whether this is the owner's latest version, the newest by creation, the order the server
     * prunes by. False once the owner has saved their profile under a newer key, which they do
     * whenever they remove a contact. It says nothing else about the newer versions.
     *
     * Most often, the key the reader holds is then one the owner replaced, the newer one having
     * never reached them, see [ProfileKeyPull][contact.ProfileKeyPull]. Not always: the newer
     * version can be one under a key the owner's vault never took, uploaded by a rotation of
     * theirs that a log-out cut short, see [ProfileRepository.rotateProfileKey] and
     * [authenticationUtils.setPendingProfileKey]. The reader's key is then the owner's current one
     * all the same, opening the version every edit of theirs goes into, which reads as not the
     * latest until the owner next rotates. Only the owner can tell the two apart, by handing over
     * their current key when asked.
     */
    @SerialName("is_latest")
    val isLatest: Boolean,
)
