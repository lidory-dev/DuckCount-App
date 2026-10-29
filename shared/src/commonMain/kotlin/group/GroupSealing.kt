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
 * perfectly. Naming the field binds that.
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
        title = unsealBound(groupKey, Context.title(groupUuid), title)?.decodeToString()
            ?.takeIf { it.length <= MAX_TITLE_LENGTH }
            ?: return null,
        description = unsealBound(groupKey, Context.description(groupUuid), description)?.decodeToString()
            ?.takeIf { it.length <= MAX_DESCRIPTION_LENGTH }
            ?: return null,
        currency = unsealBound(groupKey, Context.currency(groupUuid), currency)?.toCurrency() ?: return null,
    )

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
