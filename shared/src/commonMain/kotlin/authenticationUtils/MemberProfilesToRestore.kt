package authenticationUtils

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeEncryptedProtection
import eu.anifantakis.lib.ksafe.KSafeWriteMode
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatform.getKoin

const val MEMBER_PROFILES_TO_RESTORE = "memberProfilesToRestore"

/**
 * Records that the keys to the profiles of the members of the user's groups have yet to come back
 * from the vault, which is the case after every login, as for the contacts, see
 * [setContactsToRestore]. `MemberProfileRepository.restore` clears it once they are back.
 *
 * Suspends until the write is done rather than using the fire-and-forget `putDirect`: a marker
 * lost with the process would leave the members unnamed for the rest of the session.
 */
suspend fun setMemberProfilesToRestore() {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.put(
        key = MEMBER_PROFILES_TO_RESTORE,
        value = true,
        mode = KSafeWriteMode.Encrypted(
            protection = KSafeEncryptedProtection.DEFAULT,
            requireUnlockedDevice = true,
        ),
    )
}

fun clearMemberProfilesToRestore() {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.deleteDirect(
        key = MEMBER_PROFILES_TO_RESTORE,
    )
}

suspend fun hasMemberProfilesToRestore(): Boolean {
    val vault: KSafe = getKoin().get(named("vault"))

    return vault.get<Boolean>(MEMBER_PROFILES_TO_RESTORE, defaultValue = false)
}
