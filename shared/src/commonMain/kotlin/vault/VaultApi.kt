package vault

import networking.DuckCountClient
import networking.DuckCountRoutes
import util.NetworkError
import util.Result

/** Narrows `GET /vault` to a comma-separated set of parts. */
private const val ENTRY_KEYS_QUERY = "entry_keys"

/**
 * Remote data source for the user's vault.
 *
 * Every entry it carries is opaque to the server, an `EncryptedField` sealed on this
 * device, which the server stores, versions and hands back without ever being able
 * to read it. Sealing and unsealing is [VaultRepository]'s business; this only moves
 * bytes.
 *
 * There is no path UUID anywhere, each route is keyed by the caller's own token, so
 * a caller can only ever reach their own vault.
 */
class VaultApi(
    private val client: DuckCountClient,
) {
    /**
     * The entries of the parts named in [parts], or every entry the caller holds when
     * none are. A vault that was never written comes back empty either way.
     *
     * Naming parts matters because opening anything takes the key ring plus the part it
     * seals, two entries, while [VaultEntryKey.GROUP_MASTER_KEY] is a collection: a
     * caller that could only read in full would carry one entry per group behind every
     * such read. The server narrows in one request, so this stays a single round trip.
     */
    suspend fun getVault(
        vararg parts: VaultEntryKey,
    ): Result<ListVaultEntriesResponse, NetworkError> =
        client.get<ListVaultEntriesResponse>(
            route = DuckCountRoutes.Vault.VAULT,
            queryParams = when {
                parts.isEmpty() -> emptyMap()
                else -> mapOf(ENTRY_KEYS_QUERY to parts.joinToString(",") { it.wireName })
            },
        )

    /**
     * The entries of one part.
     *
     * One for a singleton, one per item for a collection, none for a part the caller
     * has never written.
     */
    suspend fun getVaultPart(
        entryKey: VaultEntryKey,
    ): Result<ListVaultEntriesResponse, NetworkError> =
        client.get<ListVaultEntriesResponse>(
            route = DuckCountRoutes.Vault.VAULT_PART,
            pathParams = mapOf("entry_key" to entryKey.wireName),
        )

    /**
     * Writes one entry, creating it or replacing it in place.
     *
     * A [SaveVaultEntryRequestBody.expectedVersion] that no longer matches comes back
     * as [NetworkError.CONFLICT], and nothing is written.
     */
    suspend fun saveVaultEntry(
        entryKey: VaultEntryKey,
        body: SaveVaultEntryRequestBody,
    ): Result<SaveVaultEntryResponse, NetworkError> =
        client.put<SaveVaultEntryResponse, SaveVaultEntryRequestBody>(
            route = DuckCountRoutes.Vault.VAULT_PART,
            pathParams = mapOf("entry_key" to entryKey.wireName),
            body = body,
        )

    /**
     * Applies several changes in one transaction, for a client that holds the whole
     * picture (setting a vault up, or restoring one).
     *
     * `PATCH` rather than `PUT` on the collection is the server's own choice, and it
     * matters, `PUT /vault` would mean *replace the whole vault*, so a client that
     * forgot an entry would silently destroy it.
     */
    suspend fun patchVault(body: VaultPatchBody): Result<Unit, NetworkError> =
        client.patch<Unit, VaultPatchBody>(
            route = DuckCountRoutes.Vault.VAULT,
            body = body,
        )

    /** Drops every item of a part. The root key is refused with a `400`. */
    suspend fun deleteVaultPart(entryKey: VaultEntryKey): Result<Unit, NetworkError> =
        client.delete<Unit>(
            route = DuckCountRoutes.Vault.VAULT_PART,
            pathParams = mapOf("entry_key" to entryKey.wireName),
        )

    /** Drops one item of a collection part. */
    suspend fun deleteVaultEntry(
        entryKey: VaultEntryKey,
        itemId: String,
    ): Result<Unit, NetworkError> =
        client.delete<Unit>(
            route = DuckCountRoutes.Vault.VAULT_ITEM,
            pathParams = mapOf("entry_key" to entryKey.wireName, "item_id" to itemId),
        )
}
