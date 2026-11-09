package authenticationUtils

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeEncryptedProtection
import eu.anifantakis.lib.ksafe.KSafeWriteMode
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatform.getKoin

const val BACKED_UP_MEMBER_KEYS = "backedUpMemberKeys"

/**
 * Records what `MemberProfileRepository.backUp` last found the vault holding of the signature keys
 * of the members of the user's groups, [digest] being all it compares: so that a sync that changed
 * no member asks the vault for nothing. Cleared with the session, so that a log-in looks again.
 */
suspend fun setBackedUpMemberKeys(digest: Int) {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.put(
        key = BACKED_UP_MEMBER_KEYS,
        value = digest,
        mode = KSafeWriteMode.Encrypted(
            protection = KSafeEncryptedProtection.DEFAULT,
            requireUnlockedDevice = true,
        ),
    )
}

fun clearBackedUpMemberKeys() {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.deleteDirect(
        key = BACKED_UP_MEMBER_KEYS,
    )
}

suspend fun getBackedUpMemberKeys(): Int? {
    val vault: KSafe = getKoin().get(named("vault"))

    return vault.get<Int?>(BACKED_UP_MEMBER_KEYS, defaultValue = null)
}
