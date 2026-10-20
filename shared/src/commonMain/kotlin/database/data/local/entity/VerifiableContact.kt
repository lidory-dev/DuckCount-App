package database.data.local.entity

/**
 * A current [ContactEntity], read with what checking their identity needs, see
 * [SafetyNumbers][contact.SafetyNumbers]: the safety number the user marked verified, and
 * whether they are blocked, see [IdentityConflictEntity].
 */
data class VerifiableContact(
    val verifiedSafetyNumber: String?,
    val isBlocked: Boolean,
)
