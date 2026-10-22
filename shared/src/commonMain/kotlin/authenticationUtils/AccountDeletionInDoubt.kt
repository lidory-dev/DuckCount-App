package authenticationUtils

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeEncryptedProtection
import eu.anifantakis.lib.ksafe.KSafeWriteMode
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatform.getKoin

const val ACCOUNT_DELETION_IN_DOUBT = "accountDeletionInDoubt"

/**
 * Records that a deletion of the account is out, see `account.AccountDeletion`, until its answer,
 * or anything else the server says, shows the account is still there, see
 * [clearAccountDeletionInDoubt]. The server deletes it before it answers, so an answer that never
 * comes back, or a process killed with the deletion out, leaves this device with a session the
 * server may no longer take, and nothing else to tell it so.
 *
 * What it is for is the day the server turns that session down: the user is then told their
 * account was deleted rather than that their session expired, see `DuckCountClient.expireSession`.
 * The short-lived token goes on being taken for up to 20 minutes after the deletion, so that day
 * can come long after, in a process of its own.
 *
 * Suspends until the write is done rather than using the fire-and-forget `putDirect`, so that it
 * is on disk before the deletion goes out, which is what a process killed while it is out needs.
 * Never throws: on a device locked by then, the user having locked it while their contacts were
 * told, nothing is recorded and the deletion goes out all the same, a session it leaves behind
 * then ending as an expired one.
 */
suspend fun setAccountDeletionInDoubt() {
    val vault: KSafe = getKoin().get(named("vault"))

    try {
        vault.put(
            key = ACCOUNT_DELETION_IN_DOUBT,
            value = true,
            mode = KSafeWriteMode.Encrypted(
                protection = KSafeEncryptedProtection.DEFAULT,
                requireUnlockedDevice = true,
            ),
        )
    } catch (_: IllegalStateException) {
        // Locked, see above.
    }
}

/**
 * Drops the record [setAccountDeletionInDoubt] made, once the account is known to be still
 * there: the deletion was refused, see `account.AccountDeletion`, or the server exchanged the
 * session's long-lived token, which went with the account, once the deletion was no longer on its
 * way, see `DuckCountClient.whileDeletionOut`. Ending the session drops it too, see [clearSession].
 */
fun clearAccountDeletionInDoubt() {
    val vault: KSafe = getKoin().get(named("vault"))

    vault.deleteDirect(
        key = ACCOUNT_DELETION_IN_DOUBT,
    )
}

suspend fun isAccountDeletionInDoubt(): Boolean {
    val vault: KSafe = getKoin().get(named("vault"))

    return vault.get<Boolean>(ACCOUNT_DELETION_IN_DOUBT, defaultValue = false)
}
