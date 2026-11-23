package authenticationUtils

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeEncryptedProtection
import eu.anifantakis.lib.ksafe.KSafeWriteMode
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatform.getKoin

const val PROFILE_REVISION = "profileRevision"

/**
 * Keeps [profileRevision] as the revision of the user's own profile, the time of their latest edit
 * of it in milliseconds since the epoch, which every post of the key into a group carries, see
 * `ProfileKeyUpdate.profileRevision`. `ProfileRepository.uploadProfile` owns it, and nothing else
 * has business writing here. Dropped on log out: until the next edit, posts carry none, which tells
 * the members nothing changed, and nothing did.
 *
 * Written with the fire-and-forget `putDirect`, which never throws, and read back from KSafe's
 * memory at once: it is written as the user saves their profile, before the upload, and a write
 * lost with the process only has that one edit read by the contacts alone.
 */
fun setProfileRevision(profileRevision: Long) {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.putDirect(
        key = PROFILE_REVISION,
        value = profileRevision,
        mode = KSafeWriteMode.Encrypted(
            protection = KSafeEncryptedProtection.DEFAULT,
            requireUnlockedDevice = true,
        ),
    )
}

/** Drops the revision on log out, with the rest of the session. */
fun clearProfileRevision() {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.deleteDirect(
        key = PROFILE_REVISION,
    )
}

/**
 * The revision, or null when the user has not edited their profile since this device logged in.
 * See [getRootKey] on the type argument.
 */
fun getProfileRevision(): Long? {
    val vault: KSafe = getKoin().get(named("vault"))

    return vault.getDirect<Long?>(key = PROFILE_REVISION, defaultValue = null)
}
