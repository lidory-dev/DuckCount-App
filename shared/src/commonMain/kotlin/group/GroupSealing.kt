package group

import app.lidory.duckcount.GroupLimits.MAX_DESCRIPTION_LENGTH
import app.lidory.duckcount.GroupLimits.MAX_TITLE_LENGTH
import database.data.remote.EncryptedFieldDto
import database.domain.Currency
import database.domain.CurrencyCatalog
import vault.paddedTo
import vault.sealBound
import vault.unsealBound

/**
 * What each sealed field of a group is bound to.
 *
 * Every field of one version of a group's information is sealed under the same key, and so is
 * every version sealed in the same epoch, so without this they would be interchangeable: the
 * server could put the description where the title was, and both would open perfectly. Naming the field binds that. The creator's signature over a version of the fields is
 * sealed as one more field, see [GroupVersion], so that nothing else the group holds passes for it.
 *
 * Naming the group is a second check. A Welcome the server serves under another group's UUID is
 * refused for its id, which the library derives from the UUID of the group it was written for, see
 * [GroupSync], and each group has keys of its own, under which no other group's fields open.
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

/**
 * A group's fields as the server holds them, sealed under the group's keys, see [GroupFields.seal]:
 * the title and description under the generation the version they are of was written in, and the
 * currency under the first, as the group was created, no edit changing it.
 */
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
 * keys, see [GroupVersion.seal] and [SealedGroupFields], and its revision as it is, which the
 * server reads to refuse an edit that does not come after the version it holds.
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
 * group key comes from MLS, see [mls.EpochKey], or out of a link that led back to it, see
 * [GroupKeyChain], or out of the user's copy the server kept of it, see [GroupKeyCopies].
 */
internal fun GroupFields.seal(groupKey: ByteArray, groupUuid: String): SealedGroupFields? =
    SealedGroupFields(
        title = sealBound(groupKey, Context.title(groupUuid), title.toSealedTitle()) ?: return null,
        description = sealBound(groupKey, Context.description(groupUuid), description.toSealedDescription())
            ?: return null,
        currency = sealBound(groupKey, Context.currency(groupUuid), currency.uuid.encodeToByteArray())
            ?: return null,
    )

/**
 * Opens these fields of the group [groupUuid], the title and description with [groupKey] and the
 * currency with [currencyKey], the key of the first generation, see [SealedGroupFields], or returns
 * null when any of them does not open, as that field of that group, under that key.
 *
 * All or nothing, as for a one-time expense: a group half opened would be listed under a real
 * title with a currency made up. So is one whose title or description is longer than the form
 * allows or not padded as sealing pads it, see [toSealedTitle] and [toSealedDescription], or whose
 * currency is not one the app ships with: the screens read these values as the app writes them,
 * and the group list is among the first things the app shows.
 */
internal fun SealedGroupFields.unseal(groupKey: ByteArray, currencyKey: ByteArray, groupUuid: String): GroupFields? =
    GroupFields(
        title = openTitle(groupKey = groupKey, groupUuid = groupUuid, sealed = title) ?: return null,
        description = openDescription(groupKey = groupKey, groupUuid = groupUuid, sealed = description)
            ?: return null,
        currency = openCurrency(currencyKey = currencyKey, groupUuid = groupUuid, sealed = currency) ?: return null,
    )

/**
 * The currency [sealed] opens to as that of the group [groupUuid] under [currencyKey], the key of
 * the first generation, see [SealedGroupFields], or null when it does not, or is one the app does
 * not ship with: what a group just joined, or brought back after a log-out, is shown in.
 */
internal fun openCurrency(currencyKey: ByteArray, groupUuid: String, sealed: EncryptedFieldDto): Currency? =
    unsealBound(currencyKey, Context.currency(groupUuid), sealed)?.toCurrency()

/**
 * Seals this version under [groupKey] for the group [groupUuid]: its fields as [GroupFields.seal]
 * does, and its signature as one more field, the raw bytes the library signed. Ready for `POST
 * /groups`, under the key of the first generation, and for an edit, see [EditGroupBody], under the
 * key of the group's current one, which leaves the currency as it was sealed at the creation.
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
 * Opens this version of the group [groupUuid], its title, description and signature with
 * [groupKey], the key of the generation it was written in, and its currency with [currencyKey], see
 * [SealedGroupFields.unseal], or returns null when any of them does not open, the signature as the
 * signature of that group, under that key: all or nothing again, a version with no signature being
 * one nobody can tell the writer of.
 *
 * Opened is not checked: whose signature it holds is for the caller to check against the group's
 * creator, see [openSigned].
 */
internal fun SealedGroupVersion.unseal(groupKey: ByteArray, currencyKey: ByteArray, groupUuid: String): GroupVersion? =
    GroupVersion(
        fields = fields.unseal(groupKey = groupKey, currencyKey = currencyKey, groupUuid = groupUuid) ?: return null,
        revision = revision,
        signature = openSignature(groupKey = groupKey, groupUuid = groupUuid, sealed = signature) ?: return null,
    )

/**
 * Whether this version's title, description and signature open under [groupKey] as those of the
 * group [groupUuid], see [SealedGroupVersion.unseal]: what tells the key of the generation it was
 * written in from any other, see [GroupKeyChain].
 */
internal fun SealedGroupVersion.opensUnder(groupKey: ByteArray, groupUuid: String): Boolean =
    openTitle(groupKey = groupKey, groupUuid = groupUuid, sealed = fields.title) != null &&
        openDescription(groupKey = groupKey, groupUuid = groupUuid, sealed = fields.description) != null &&
        openSignature(groupKey = groupKey, groupUuid = groupUuid, sealed = signature) != null

/**
 * Opens this version of the group [groupUuid] with [groupKey], the key of the generation it was
 * written in, as [SealedGroupVersion.unseal] does, but for its currency, [currency], the group's
 * own, which no edit changes: what a group this device holds is held to, as its history is, see
 * [GroupHistoryVersionDto.unseal]. The creator signed the currency with every version, see
 * [GroupVersion], so a version of another currency does not verify as theirs.
 */
internal fun SealedGroupVersion.unseal(groupKey: ByteArray, groupUuid: String, currency: Currency): GroupVersion? =
    GroupVersion(
        fields = GroupFields(
            title = openTitle(groupKey = groupKey, groupUuid = groupUuid, sealed = fields.title) ?: return null,
            description = openDescription(groupKey = groupKey, groupUuid = groupUuid, sealed = fields.description)
                ?: return null,
            currency = currency,
        ),
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
 * every version of a group sealed in the same epoch is sealed under the same key, with the same
 * contexts, so a field moved from one to another opens all the same, and only fails to verify as
 * the version it was moved to.
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
 * The most bytes a group's title takes in UTF-8: three for each of the UTF-16 code units
 * [MAX_TITLE_LENGTH] counts, a surrogate pair taking four for its two.
 */
private const val SEALED_TITLE_SIZE = MAX_TITLE_LENGTH * 3

/**
 * A group's title as it is sealed: padded with spaces to [SEALED_TITLE_SIZE] bytes, as an expense's
 * is, see `expense.OneTimeExpenseSealing`, for the server not to read how long it is from the length
 * of this field alone. A group's title and description are saved without the whitespace around
 * them, see [GroupCreator] and [GroupEditor], so that opening them takes off nothing but the padding.
 * Only what is sealed is padded: the creator signs what they open to, see [signedAs].
 */
private fun String.toSealedTitle(): ByteArray = encodeToByteArray().paddedTo(SEALED_TITLE_SIZE)

/**
 * The most bytes a group's description takes in UTF-8: three for each of the UTF-16 code units
 * [MAX_DESCRIPTION_LENGTH] counts.
 */
private const val SEALED_DESCRIPTION_SIZE = MAX_DESCRIPTION_LENGTH * 3

/**
 * A group's description as it is sealed: padded with spaces to [SEALED_DESCRIPTION_SIZE] bytes, for
 * the server not to read how long it is from the length of this field alone. An expense's is padded
 * to three sizes instead, see `expense.OneTimeExpenseSealing`, a group holding many expenses that
 * would each pay for the longest. A user has few groups, so that one size costs little here.
 */
private fun String.toSealedDescription(): ByteArray = encodeToByteArray().paddedTo(SEALED_DESCRIPTION_SIZE)

/**
 * The title [sealed] opens to as that of the group [groupUuid] under [groupKey], or null when it
 * does not, was not padded as [toSealedTitle] pads one, or is longer than the form allows, see
 * [SealedGroupFields.unseal].
 */
private fun openTitle(groupKey: ByteArray, groupUuid: String, sealed: EncryptedFieldDto): String? =
    unsealBound(groupKey, Context.title(groupUuid), sealed)
        ?.takeIf { it.size == SEALED_TITLE_SIZE }
        ?.decodeToString()
        ?.trimEnd(' ')
        ?.takeIf { it.length <= MAX_TITLE_LENGTH }

/** The description [sealed] opens to, as [openTitle] opens a title, padded as [toSealedDescription] pads one. */
private fun openDescription(groupKey: ByteArray, groupUuid: String, sealed: EncryptedFieldDto): String? =
    unsealBound(groupKey, Context.description(groupUuid), sealed)
        ?.takeIf { it.size == SEALED_DESCRIPTION_SIZE }
        ?.decodeToString()
        ?.trimEnd(' ')
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
