package group

import app.lidory.duckcount.GroupLimits.MAX_DESCRIPTION_LENGTH
import app.lidory.duckcount.GroupLimits.MAX_TITLE_LENGTH
import database.data.remote.EncryptedFieldDto
import database.domain.Currency
import database.domain.CurrencyCatalog
import vault.sealBound
import vault.unsealBound

/**
 * What each sealed field of a group is bound to.
 *
 * Every field of one group is sealed under the same key, so without this they would be
 * interchangeable: the server could put the description where the title was, and both would open
 * perfectly. Naming the field binds that. The creator's signature over a version of the fields is
 * sealed as one more field, see [GroupVersion], so that nothing else the group holds passes for it.
 *
 * Naming the group is a second check. A Welcome the server serves under another group's UUID is
 * refused for its id, which the library derives from the UUID of the group it was written for, see
 * [GroupSync], and each group has a key of its own, under which no other group's fields open.
 * Bound to the group they were sealed for, the fields open as no other group's even under a key
 * two groups were given.
 */
private object Context {
    fun title(groupUuid: String) = "group:$groupUuid:title"

    fun description(groupUuid: String) = "group:$groupUuid:description"

    fun currency(groupUuid: String) = "group:$groupUuid:currency"

    fun signature(groupUuid: String) = "group:$groupUuid:signature"
}

/** A group's fields, as its members read them. */
internal class GroupFields(
    val title: String,
    val description: String,
    val currency: Currency,
)

/** A group's fields as the server holds them, sealed under the group's key, see [GroupFields.seal]. */
internal class SealedGroupFields(
    val title: EncryptedFieldDto,
    val description: EncryptedFieldDto,
    val currency: EncryptedFieldDto,
)

/**
 * One version of a group's information: its [fields], which [revision] of them they are, and the
 * signature its creator made over both with their MLS identity, see [signedAs] and `group_information`
 * in lib-duckcount. Every member holds the group's key, and could seal fields of their own, so the
 * seal says nothing of who wrote a version: the signature does.
 */
internal class GroupVersion(
    val fields: GroupFields,
    val revision: Long,
    val signature: ByteArray,
)

/**
 * A [GroupVersion] as the server holds it: its fields and its signature sealed under the group's
 * key, see [GroupVersion.seal], and its revision as it is, which the server reads to refuse an edit
 * that does not come after the version it holds.
 */
internal class SealedGroupVersion(
    val fields: SealedGroupFields,
    val revision: Long,
    val signature: EncryptedFieldDto,
)

/**
 * Seals these fields under [groupKey] for the group [groupUuid], ready for `POST /groups`.
 *
 * The currency is sealed as a one-time expense seals its own, see [toCurrency]: by the UUID the
 * catalogue gives it, and nothing else.
 *
 * Returns null only when [groupKey] is not a 32-byte key, a caller bug rather than a state: every
 * group key comes from `generateSymmetricEncryptionKey`, or out of the Welcome or the message that
 * handed it over, or out of the user's copy the server kept of it, see [GroupKeyCopies].
 */
internal fun GroupFields.seal(groupKey: ByteArray, groupUuid: String): SealedGroupFields? =
    SealedGroupFields(
        title = sealBound(groupKey, Context.title(groupUuid), title.encodeToByteArray()) ?: return null,
        description = sealBound(groupKey, Context.description(groupUuid), description.encodeToByteArray())
            ?: return null,
        currency = sealBound(groupKey, Context.currency(groupUuid), currency.uuid.encodeToByteArray())
            ?: return null,
    )

/**
 * Opens these fields of the group [groupUuid] with [groupKey], or returns null when any of them
 * does not open, as that field of that group, under that key.
 *
 * All or nothing, as for a one-time expense: a group half opened would be listed under a real
 * title with a currency made up. So is one whose title or description is longer than the form
 * allows, or whose currency is not one the app ships with: the screens read these values as the
 * app writes them, and the group list is among the first things the app shows.
 */
internal fun SealedGroupFields.unseal(groupKey: ByteArray, groupUuid: String): GroupFields? =
    GroupFields(
        title = openTitle(groupKey = groupKey, groupUuid = groupUuid, sealed = title) ?: return null,
        description = openDescription(groupKey = groupKey, groupUuid = groupUuid, sealed = description)
            ?: return null,
        currency = unsealBound(groupKey, Context.currency(groupUuid), currency)?.toCurrency() ?: return null,
    )

/**
 * Seals this version under [groupKey] for the group [groupUuid]: its fields as [GroupFields.seal]
 * does, and its signature as one more field, the raw bytes the library signed. Ready for `POST
 * /groups`, an edit, see [EditGroupBody], and a key rotation, which seals the same version again
 * under the new key, see [RotateGroupKeyBody], and each one its history holds, see
 * [PreviousGroupVersionBody].
 *
 * Returns null only when [groupKey] is not a 32-byte key, as for [GroupFields.seal].
 */
internal fun GroupVersion.seal(groupKey: ByteArray, groupUuid: String): SealedGroupVersion? =
    SealedGroupVersion(
        fields = fields.seal(groupKey = groupKey, groupUuid = groupUuid) ?: return null,
        revision = revision,
        signature = sealBound(groupKey, Context.signature(groupUuid), signature) ?: return null,
    )

/**
 * Opens this version of the group [groupUuid] with [groupKey], or returns null when its fields do
 * not open, see [SealedGroupFields.unseal], or its signature does not, as the signature of that
 * group, under that key: all or nothing again, a version with no signature being one nobody can
 * tell the writer of.
 *
 * Opened is not checked: whose signature it holds is for the caller to check against the group's
 * creator, see [openSigned].
 */
internal fun SealedGroupVersion.unseal(groupKey: ByteArray, groupUuid: String): GroupVersion? =
    GroupVersion(
        fields = fields.unseal(groupKey = groupKey, groupUuid = groupUuid) ?: return null,
        revision = revision,
        signature = openSignature(groupKey = groupKey, groupUuid = groupUuid, sealed = signature) ?: return null,
    )

/**
 * Opens this version of the group [groupUuid] as its history holds it with [groupKey], its
 * currency [currency], the group's own, or returns null when its title, its description or its
 * signature does not open, as [SealedGroupVersion.unseal] opens the version the server lists: each
 * as that field of that group, under that key, the title and description no longer than the form
 * allows.
 *
 * The history holds no currency, which no edit changes: the group's own is what the creator signed
 * with every version, see [GroupVersion], so a version is checked with it, see [openSigned].
 * Opened is not checked here either, and the signature is what holds each field to its version:
 * every version of a group is sealed under the same key, with the same contexts, so a field moved
 * from one to another opens all the same, and only fails to verify as the version it was moved to.
 */
internal fun GroupHistoryVersionDto.unseal(groupKey: ByteArray, groupUuid: String, currency: Currency): GroupVersion? =
    GroupVersion(
        fields = GroupFields(
            title = openTitle(groupKey = groupKey, groupUuid = groupUuid, sealed = title) ?: return null,
            description = openDescription(groupKey = groupKey, groupUuid = groupUuid, sealed = description)
                ?: return null,
            currency = currency,
        ),
        revision = revision,
        signature = openSignature(groupKey = groupKey, groupUuid = groupUuid, sealed = signature) ?: return null,
    )

/**
 * The title [sealed] opens to as that of the group [groupUuid] under [groupKey], or null when it
 * does not, or is longer than the form allows, see [SealedGroupFields.unseal].
 */
private fun openTitle(groupKey: ByteArray, groupUuid: String, sealed: EncryptedFieldDto): String? =
    unsealBound(groupKey, Context.title(groupUuid), sealed)?.decodeToString()
        ?.takeIf { it.length <= MAX_TITLE_LENGTH }

/** The description [sealed] opens to, as [openTitle] opens a title. */
private fun openDescription(groupKey: ByteArray, groupUuid: String, sealed: EncryptedFieldDto): String? =
    unsealBound(groupKey, Context.description(groupUuid), sealed)?.decodeToString()
        ?.takeIf { it.length <= MAX_DESCRIPTION_LENGTH }

/** The signature [sealed] opens to as that of a version of the group [groupUuid] under [groupKey]. */
private fun openSignature(groupKey: ByteArray, groupUuid: String, sealed: EncryptedFieldDto): ByteArray? =
    unsealBound(groupKey, Context.signature(groupUuid), sealed)

/**
 * The currency of [CurrencyCatalog] whose UUID was sealed, or null for one the app does not ship
 * with.
 *
 * Only the UUID is sealed, as for a one-time expense: it is the one field the catalogue promises
 * never changes between releases, so the receiver's own entry stands for the rest, and every one is
 * the same length. A symbol, or whether it has subunits, would tell the server which currency a
 * group is in by the length of this field alone.
 */
private fun ByteArray.toCurrency(): Currency? {
    val uuid = decodeToString()

    return CurrencyCatalog.defaultCurrencies.firstOrNull { it.uuid == uuid }
}
