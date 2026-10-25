package vault

import vault.VaultEntryKey.Companion.parse


/**
 * The parts the user's vault is made of.
 *
 * Mirrors the server's `VaultEntryKey`, whose namespace is closed, a part the
 * server does not know is a `400`, not a `404`. Reading goes the other way, a
 * newer server may hand back a part this build has never heard of, so a stored
 * entry is matched with [parse], which answers null rather than throwing.
 *
 * A part is either a **singleton**, whose only entry carries the empty item id, or
 * a **collection**, whose entries the client names itself. That choice is ours, the
 * server stores every entry identically.
 */
enum class VaultEntryKey(val wireName: String) {

    /**
     * The key that seals [KEY_RING]. Singleton.
     *
     * The one part sealed under the key derived from the user's password, so the
     * only one a password change rewrites, and the only one that cannot be deleted.
     * Its plaintext is exactly one key and must stay that way, an older app version
     * has to be able to re-seal it without understanding anything a newer one built
     * on top of it.
     */
    ROOT_KEY("root_key"),

    /**
     * The keys that seal every other part. Singleton.
     *
     * Sealed under [ROOT_KEY] rather than under the password, so a password change
     * never touches it. See [KeyRing] for the two rules that keep a key a newer app
     * version added from being dropped by an older one.
     */
    KEY_RING("key_ring"),

    /**
     * The key of each group the user is a member of, which the group's fields on the server are
     * sealed under, see `group.GroupSealing`. Collection, keyed by the group's UUID, each item
     * sealed under this part's key and bound to its UUID, see `VaultRepository.setGroupKey`.
     *
     * A log-out wipes the local database, and the device's copy of each key with it, while the
     * Welcome that handed a key over is acknowledged once read: without this entry, nothing would
     * open a group's fields again.
     */
    GROUP_MASTER_KEY("group_master_key"),

    /**
     * The key the user's copies of their one-time expense keys are sealed under, held in
     * [KEY_RING] like every part's key. The copies themselves are stored by the server with
     * each expense, not in the vault, so no vault entry carries this name. See
     * `OneTimeExpenseKeyRepository`.
     */
    ONE_TIME_EXPENSE_KEY("one_time_expense_key"),

    /**
     * The key that unseals the user's own profile. Singleton.
     *
     * Unlike every other part, the key inside this one is meant to leave the vault,
     * it is what a contact is given, through a contact request or its acceptance, to
     * read the profile it seals.
     *
     * Replaced whenever the user removes a contact, so the one removed reads nothing
     * the user writes after, see `ProfileRepository.rotateProfileKey`, and the new one
     * is handed to every contact left, see `ProfileKeyDelivery`. The server keeps the
     * profile as it was under each of the last keys, so whoever holds an older one
     * reads it as it stood when that key was replaced.
     */
    PROFILE_ENCRYPTION_KEY("profile_encryption_key"),

    /**
     * The handle the user reserved to be discoverable by. Singleton.
     *
     * The server holds only its digest, so it can answer a contact lookup without
     * ever learning the handle and cannot give it back either. Without this entry the
     * pre-image lives nowhere but the device that typed it, and a reinstall takes it
     * with it: the account stays discoverable while the app can no longer say under
     * what.
     */
    DISCOVERY_USERNAME("discovery_username"),

    /**
     * The user's blocklist. Singleton, sealed whole rather than split into an entry
     * per blocked user, so the server never learns how many there are.
     */
    BLOCKED_USERS("blocked_users"),

    /**
     * The user's long-term MLS signature key pair. Singleton.
     *
     * This key is the user's MLS identity: every peer pins its public half the first
     * time they see it and refuses any other from then on. The device keeps it too,
     * but a log-out wipes the local database and a reinstall takes it along, and a
     * freshly generated key would look to every existing contact exactly like an
     * impersonation attempt. Restoring it from here keeps the same identity instead.
     */
    MLS_SIGNATURE_KEY("mls_signature_key"),

    /**
     * The user's contacts, each with the profile encryption key they handed over, the MLS
     * identity pinned for them, which of the user's own profile keys they were handed last,
     * and the safety number the user verified with them. Singleton, see [ContactBook].
     *
     * Sealed whole rather than split into an entry per contact, so the server learns
     * neither who they are nor how many. It keeps no other record of them: the request
     * that made someone a contact is deleted once answered, so without this entry a
     * log-out or a reinstall would lose every key a contact ever handed over.
     *
     * It also keeps the name of each former contact, whichever of the two removed the other
     * or whose account was deleted, and nothing else of them, see [ContactBook.formerContacts]:
     * the key to their profile goes with the contact, and the server deletes a deleted
     * account's profile along with it, so without this entry a log-out or a reinstall would
     * lose who they were in every expense the two of them share.
     */
    CONTACTS("contacts");

    companion object {
        /** The part [wireName] names, or null for one this build does not know. */
        fun parse(wireName: String): VaultEntryKey? =
            entries.firstOrNull { it.wireName == wireName }
    }
}

/** The item id every entry of a singleton part carries. */
const val SINGLETON_ITEM_ID = ""
