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

    /** The master key of one MLS group. Collection, keyed by the group's UUID. */
    GROUP_MASTER_KEY("group_master_key"),

    /**
     * The key that unseals the user's own profile. Singleton.
     *
     * Unlike every other part, the key inside this one is meant to leave the vault,
     * it is what a contact is given, through a contact request, to read the profile
     * it seals.
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
    MLS_SIGNATURE_KEY("mls_signature_key");

    companion object {
        /** The part [wireName] names, or null for one this build does not know. */
        fun parse(wireName: String): VaultEntryKey? =
            entries.firstOrNull { it.wireName == wireName }
    }
}

/** The item id every entry of a singleton part carries. */
const val SINGLETON_ITEM_ID = ""
