package group

import database.data.local.entity.GroupEntity
import mls.pinnedSignatureKeyFor
import uniffi.lib_duckcount.GroupInformation
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import uniffi.lib_duckcount.verifyGroupInformation

/** The revision a group is created at, see [GroupVersion]. Each edit of its creator's adds one, see [GroupEditor]. */
internal const val FIRST_GROUP_REVISION = 1L

/**
 * These fields as [revision] of the group [groupUuid], signed by the user [client] is opened for:
 * the version a group they create starts at, see [GroupCreator], and each edit of theirs, see
 * [GroupEditor]. What is signed is what the fields open to, not the bytes the server stores, so a
 * version signed once is as good under whichever key of the group's it was sealed under, see
 * `group_information` in lib-duckcount, and Ed25519 signs the same version the same way every
 * time: an edit saved again after its answer was lost signs the same version again.
 *
 * Returns null only when the library refuses to sign, a caller bug rather than a state, as for a
 * one-time expense, see [expense.sealOneTime]: every field is a string, which it encodes whatever
 * it holds.
 */
internal fun GroupFields.signedAs(client: MlsClient, groupUuid: String, revision: Long): GroupVersion? {
    val signature = try {
        client.signGroupInformation(information = informationOf(groupUuid = groupUuid, revision = revision))
    } catch (_: MlsException) {
        return null
    }

    return GroupVersion(fields = this, revision = revision, signature = signature)
}

/**
 * Whether this version of the group [groupUuid] is one [ownerUuid] signed with the private half of
 * [ownerSignatureKey]: nobody else's signature, nor theirs over anything else, another group's
 * version or another revision of this one among it, verifies.
 */
internal fun GroupVersion.isSignedBy(ownerUuid: String, ownerSignatureKey: ByteArray, groupUuid: String): Boolean =
    verifyGroupInformation(
        ownerUuid = ownerUuid,
        ownerSignatureKey = ownerSignatureKey,
        information = fields.informationOf(groupUuid = groupUuid, revision = revision),
        signature = signature,
    )

/**
 * The MLS signature key the versions of a group created by [creatorUuid] are checked against, see
 * [isSignedByCreator]: the user's own for a group the user, [selfUuid], created, and the one this
 * device pinned for anybody else, whose MLS identity the members' devices hold the creator to, see
 * [GroupEntity.ownerUuid]; or null when it holds no pin for them.
 *
 * Read once for a group, before anything that could not be undone: a Welcome joined and not saved
 * is lost, see [GroupSync].
 *
 * Throws [MlsException] when the pin could not be read.
 */
internal suspend fun MlsClient.creatorKeyOf(creatorUuid: String, selfUuid: String): ByteArray? =
    if (creatorUuid == selfUuid) signaturePublicKey() else pinnedSignatureKeyFor(peerUuid = creatorUuid)

/**
 * Whether this version of the group [groupUuid] is one its creator, [creatorUuid], signed, under
 * [creatorKey], the key [creatorKeyOf] reads for them. Every member holds the group's key, and can
 * seal fields of their own, which the server can list, but none of them can sign as its creator.
 *
 * A creator this device holds no pin for, [creatorKey] null, is taken at the version's word, as
 * there is nothing to hold it against, as for a one-time expense whose creditor it holds no pin
 * for, see [expense.OneTimeExpenseKeyRepository.restore]. That is a group brought back after a
 * log-out whose creator is not one of the user's contacts: a log-out leaves this device no pin but
 * those the vault holds for them, see [mls.MlsIdentityRepository], and every group joined from a
 * Welcome is joined from somebody pinned, see [GroupSync], whom nothing unpins short of a log-out.
 * The server can then have such a group show whatever another member sealed, as it could anyway by
 * serving that member's identity for its creator to a device that never pinned them.
 *
 * The creator is whoever this device records as such, see [GroupEntity.ownerUuid], or, for a
 * group brought back after a log-out, the one the server lists, see [GroupDto.creatorUuid]: not
 * its owner, which a deletion of the creator's account hands over to another member, under whose
 * key the creator's versions do not hold up.
 */
internal fun GroupVersion.isSignedByCreator(creatorUuid: String, creatorKey: ByteArray?, groupUuid: String): Boolean =
    creatorKey == null || isSignedBy(ownerUuid = creatorUuid, ownerSignatureKey = creatorKey, groupUuid = groupUuid)

/**
 * This version of the group [groupUuid] opened under [groupKey], the key of the generation it was
 * written in, and [currencyKey], the key of the first, see [SealedGroupVersion.unseal], when its
 * creator, [creatorUuid], signed it, see [isSignedByCreator], or null otherwise: what a group is
 * joined, or brought back, with, see [GroupSync].
 */
internal fun SealedGroupVersion.openSigned(
    groupKey: ByteArray,
    currencyKey: ByteArray,
    groupUuid: String,
    creatorUuid: String,
    creatorKey: ByteArray?,
): GroupVersion? =
    unseal(groupKey = groupKey, currencyKey = currencyKey, groupUuid = groupUuid)
        ?.takeIf { it.isSignedByCreator(creatorUuid = creatorUuid, creatorKey = creatorKey, groupUuid = groupUuid) }

/**
 * The version of the information of [group], which this device holds, that the server lists in
 * this listing, when it is newer than the one this device shows, see [GroupEntity.infoRevision],
 * opens under the key of the generation the listing says it was written in, which [keyOf] gives,
 * with the group's own currency, see [SealedGroupVersion.unseal], and was signed by its creator,
 * whose key [creatorKey] is, see [isSignedByCreator]; or null otherwise, [keyOf] failing included.
 * What the sync shows of every group it holds, see [GroupSync], and what an edit the server found
 * outdated shows, see [GroupEditor].
 *
 * [keyOf] is [GroupKeyChain.keyOf] for that group, asked only for a newer version, and told what
 * opens under the key it is after: that version.
 *
 * An equal revision is the version shown. An older one is a version this device saw replaced,
 * which the server would have it show again by holding the newer one back: never gone back to.
 */
internal suspend fun GroupDto.newerThan(
    group: GroupEntity,
    creatorKey: ByteArray?,
    keyOf: suspend (generation: Long, opens: (key: ByteArray) -> Boolean) -> ByteArray?,
): GroupVersion? {
    val sealed = sealedVersion().takeIf { it.revision > group.infoRevision } ?: return null

    val groupKey = keyOf(informationKeyGeneration) { key ->
        sealed.unseal(groupKey = key, groupUuid = group.uuid, currency = group.currency) != null
    } ?: return null

    return sealed.unseal(groupKey = groupKey, groupUuid = group.uuid, currency = group.currency)
        ?.takeIf {
            it.isSignedByCreator(creatorUuid = group.ownerUuid, creatorKey = creatorKey, groupUuid = group.uuid)
        }
}

/**
 * This version of the information of [group], which this device holds, as the group's history
 * holds it, see [GroupApi.history], opened under [groupKey], the key of the generation it names,
 * with the group's currency, see [GroupHistoryVersionDto.unseal], when its creator signed it, under
 * [creatorKey], see [isSignedByCreator], or null otherwise: each version the history shows, see
 * [GroupHistory].
 *
 * Whatever its revision: the history is no version to show in place of the one this device shows,
 * which [newerThan] alone gives, and every version in it that the creator signed is one they made.
 */
internal fun GroupHistoryVersionDto.openSigned(
    group: GroupEntity,
    groupKey: ByteArray,
    creatorKey: ByteArray?,
): GroupVersion? =
    unseal(groupKey = groupKey, groupUuid = group.uuid, currency = group.currency)
        ?.takeIf {
            it.isSignedByCreator(creatorUuid = group.ownerUuid, creatorKey = creatorKey, groupUuid = group.uuid)
        }

/** The version of this group's information as the server holds it, sealed, see [SealedGroupVersion]. */
internal fun GroupDto.sealedVersion() = SealedGroupVersion(
    fields = SealedGroupFields(title = title, description = description, currency = currency),
    revision = revision,
    signature = signature,
)

/**
 * What the creator signs of these fields as [revision] of the group [groupUuid]: what they open
 * to, the currency by the UUID the catalogue gives it, as it is sealed, see [GroupFields.seal].
 */
private fun GroupFields.informationOf(groupUuid: String, revision: Long) = GroupInformation(
    groupUuid = groupUuid,
    revision = revision.toULong(),
    title = title,
    description = description,
    currencyUuid = currency.uuid,
)
