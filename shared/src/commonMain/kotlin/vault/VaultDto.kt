package vault

import database.data.remote.EncryptedFieldDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One entry of the vault as the server hands it back.
 *
 * [entryKey] stays a `String` rather than a [VaultEntryKey] on purpose, the server
 * may hold a part a newer build added, and a whole sync must not fail to decode
 * because one entry in it is unknown. Callers match it with [VaultEntryKey.parse].
 *
 * [version] starts at 1 and increments on every write, which is what lets a client
 * notice that another device changed the part underneath it.
 */
@Serializable
data class VaultEntryDto(
    @SerialName("entry_key")
    val entryKey: String,

    val content: EncryptedFieldDto,

    val version: Long,

    @SerialName("updated_at")
    val updatedAt: String,
)

/** Response of both `GET /vault` and `GET /vault/{entry_key}`. */
@Serializable
data class ListVaultEntriesResponse(
    val entries: List<VaultEntryDto>,
)

/**
 * Body of `PUT /vault/{entry_key}`.
 *
 * [expectedVersion] is the version the caller last read: sending it has the write
 * refused with a `409` when another device changed the part since, omitting it
 * writes unconditionally.
 */
@Serializable
data class SaveVaultEntryRequestBody(
    val content: EncryptedFieldDto,

    @SerialName("expected_version")
    val expectedVersion: Long? = null,
)

/** Response of `PUT /vault/{entry_key}`, the version the entry now sits at. */
@Serializable
data class SaveVaultEntryResponse(
    val version: Long,
)

/**
 * One entry of a `PATCH /vault` batch.
 *
 * It carries no expected version, a batch is the path taken when the client holds
 * the whole picture (a first setup, or a restore) so every write in it is
 * unconditional. Per-entry optimistic concurrency belongs on
 * [SaveVaultEntryRequestBody].
 */
@Serializable
data class VaultEntryUploadDto(
    @SerialName("entry_key")
    val entryKey: String,

    val content: EncryptedFieldDto,
)

/** One entry a `PATCH /vault` batch drops. */
@Serializable
data class VaultEntryRefDto(
    @SerialName("entry_key")
    val entryKey: String,
)

/**
 * Body of `PATCH /vault`, several changes applied in one transaction.
 *
 * Either list may be empty, but not both, and no `entry_key` may appear twice across
 * the two. Nothing is persisted unless every change succeeds.
 */
@Serializable
data class VaultPatchBody(
    val upserts: List<VaultEntryUploadDto> = emptyList(),
    val deletions: List<VaultEntryRefDto> = emptyList(),
)
