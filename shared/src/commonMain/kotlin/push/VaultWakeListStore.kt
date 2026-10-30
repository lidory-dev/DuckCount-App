package push

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeEncryptedProtection
import eu.anifantakis.lib.ksafe.KSafeWriteMode

private const val CONTACTS = "wakeListContacts"

private const val REQUESTED = "wakeListRequested"

private const val CO_MEMBERS = "wakeListCoMembers"

/**
 * Who may wake the user, kept in the vault where [PushSignal.mayWake] can read it with the
 * screen locked, see [WakeList].
 *
 * Encrypted like everything else in it, under a Keystore key, but written without the
 * unlocked-device requirement the session and the keys carry: the whole point is to look a
 * sender up while the phone is in a pocket. The vault decrypts an entry only once it is read,
 * so reading these never touches the entries a locked device cannot open.
 *
 * Nobody is on it until the first list is written, and a list that cannot be read names nobody.
 */
internal class VaultWakeListStore(private val vault: KSafe) : WakeListStore {

    override fun isContact(userUuid: String): Boolean = userUuid in listed(key = CONTACTS)

    override fun isRequested(userUuid: String): Boolean = userUuid in listed(key = REQUESTED)

    override fun isCoMember(userUuid: String): Boolean = userUuid in listed(key = CO_MEMBERS)

    /** Written before this returns, so that a removal outlives the process. */
    override suspend fun replace(contacts: Set<String>, requested: Set<String>, coMembers: Set<String>) {
        vault.put(key = CONTACTS, value = contacts, mode = READABLE_WHILE_LOCKED)
        vault.put(key = REQUESTED, value = requested, mode = READABLE_WHILE_LOCKED)
        vault.put(key = CO_MEMBERS, value = coMembers, mode = READABLE_WHILE_LOCKED)
    }

    /**
     * Gone before this returns, since the process is usually killed right after, see
     * [authenticationUtils.SessionLeftovers.clear].
     */
    override suspend fun clear() {
        vault.delete(key = CONTACTS)
        vault.delete(key = REQUESTED)
        vault.delete(key = CO_MEMBERS)
    }

    private fun listed(key: String): Set<String> = vault.getDirect<Set<String>>(key = key, defaultValue = emptySet())

    private companion object {
        val READABLE_WHILE_LOCKED = KSafeWriteMode.Encrypted(
            protection = KSafeEncryptedProtection.DEFAULT,
            requireUnlockedDevice = false,
        )
    }
}
