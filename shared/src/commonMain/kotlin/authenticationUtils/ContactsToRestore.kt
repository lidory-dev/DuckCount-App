package authenticationUtils

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeEncryptedProtection
import eu.anifantakis.lib.ksafe.KSafeWriteMode
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatform.getKoin

const val CONTACTS_TO_RESTORE = "contactsToRestore"

/**
 * Records that the user's contacts have yet to come back from the vault, which is the case
 * after every login: the device starts out without them, and the server keeps no other
 * record of who they are. `ContactRepository.restore` clears it once they are back.
 *
 * Suspends until the write is done rather than using the fire-and-forget `putDirect`: a
 * marker lost with the process would leave the contacts behind for the rest of the session.
 */
suspend fun setContactsToRestore() {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.put(
        key = CONTACTS_TO_RESTORE,
        value = true,
        mode = KSafeWriteMode.Encrypted(
            protection = KSafeEncryptedProtection.DEFAULT,
            requireUnlockedDevice = true,
        ),
    )
}

fun clearContactsToRestore() {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.deleteDirect(
        key = CONTACTS_TO_RESTORE,
    )
}

suspend fun hasContactsToRestore(): Boolean {
    val vault: KSafe = getKoin().get(named("vault"))

    return vault.get<Boolean>(CONTACTS_TO_RESTORE, defaultValue = false)
}
