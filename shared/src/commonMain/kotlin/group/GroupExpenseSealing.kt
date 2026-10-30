package group

import app.lidory.duckcount.ExpenseLimits.MAX_DESCRIPTION_LENGTH
import app.lidory.duckcount.ExpenseLimits.MAX_TITLE_LENGTH
import app.lidory.duckcount.validateExpense
import database.data.local.entity.ExpenseWithBeneficiariesEntity
import database.data.local.entity.GroupEntity
import database.data.mappers.toExpenseWithBeneficiariesEntity
import database.data.remote.EncryptedFieldDto
import database.domain.Beneficiary
import database.domain.Currency
import database.domain.ExpenseWithBeneficiaries
import expense.isCanonicalTimestamp
import expense.isCanonicalUuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mls.pinnedSignatureKeyFor
import uniffi.lib_duckcount.GroupExpenseBeneficiary
import uniffi.lib_duckcount.GroupExpenseVersion
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import uniffi.lib_duckcount.verifyGroupExpense
import util.NetworkError
import util.Result
import vault.sealBound
import vault.unsealBound
import kotlin.io.encoding.Base64

/** The revision a group expense is created at, see [GroupExpenseVersion]. Each version since adds one. */
internal const val FIRST_GROUP_EXPENSE_REVISION = 1L

/** Unknown keys are ignored, so a field a newer build added does not make a version unreadable. */
private val json = Json { ignoreUnknownKeys = true }

/**
 * What the one sealed field of a version of the expense [expenseUuid] of the group [groupUuid] is
 * bound to, see [sealBound].
 *
 * Every expense of a group is sealed under the group's key, so without it the server could serve
 * one expense's version as another's, or as one of another group the user is in under a key the two
 * share, and it would open perfectly. The signature covers both UUIDs as well, see `group_expense`
 * in lib-duckcount, but only once a version opens: bound, it does not even do that.
 */
private fun contextOf(groupUuid: String, expenseUuid: String) = "group_expense:$groupUuid:$expenseUuid"

/** One version of a group expense, opened and checked, see [GroupExpenseOpener]. */
internal class OpenedGroupExpense(
    /** What it says, in the group's currency, its group set, deleted or not. */
    val expense: ExpenseWithBeneficiaries,

    /** The revision its author signed it at, see [FIRST_GROUP_EXPENSE_REVISION]. */
    val revision: Long,

    /** The member who wrote it, as they signed it. */
    val authorUuid: String,
) {
    /** The row it is stored as, see [database.data.local.dao.ExpenseDao.storeGroupExpenses]. */
    fun toEntity(): ExpenseWithBeneficiariesEntity {
        val entity = expense.toExpenseWithBeneficiariesEntity()

        return entity.copy(expense = entity.expense.copy(revision = revision, authorUuid = authorUuid))
    }
}

/**
 * A version of a group expense unsealed, see [unsealGroupExpense]: what its author signed, as the
 * library takes it, who it says they are, and the signature, none of it checked yet.
 */
internal class SignedGroupExpense(
    val version: GroupExpenseVersion,
    val authorUuid: String,
    val signature: ByteArray,
)

/**
 * Seals this expense, of the group [groupUuid], under [groupKey], the group's key of the generation
 * it is sent with, as [revision], signed by [client], the user [authorUuid]'s MLS identity: the
 * `content` of [CreateGroupExpenseBody] and [UpdateGroupExpenseBody].
 *
 * One sealed field holds it all, its title and description, amount and date, creditor and
 * beneficiaries, whether it is a settlement and whether it is deleted, with its author and their
 * signature, see [SealedGroupExpense]: the server learns nothing of who paid or who benefits, nor
 * who wrote the version. The group, the expense and the revision are not in it: the route names the
 * first two and the server lists the third, and the signature holds every one of them to what the
 * author signed, see [unsealGroupExpense].
 *
 * Signed over what it opens to, see `group_expense` in lib-duckcount, so that a version stays good
 * whichever key it is read with. Every member holds [groupKey], and could seal a version of their
 * own: only the signature says who wrote it.
 *
 * Returns null only when [groupKey] is not a 32-byte key or the library refuses to sign, both caller
 * bugs rather than states, as for a group's information, see [signedAs].
 */
internal fun ExpenseWithBeneficiaries.sealGroupExpense(
    groupUuid: String,
    revision: Long,
    groupKey: ByteArray,
    client: MlsClient,
    authorUuid: String,
): EncryptedFieldDto? {
    val signature = try {
        client.signGroupExpense(version = versionOf(groupUuid = groupUuid, revision = revision))
    } catch (_: MlsException) {
        return null
    }

    val content = SealedGroupExpense(
        title = title,
        description = description,
        amount = amount,
        timestamp = timestamp,
        creditorUuid = creditorUuid,
        beneficiaries = beneficiaries.map { beneficiary ->
            SealedGroupExpenseBeneficiary(
                userUuid = beneficiary.userUuid,
                share = beneficiary.share,
                isShareFixed = beneficiary.isShareFixed.toSealedFlag(),
            )
        },
        isSettlement = isSettlement.toSealedFlag(),
        isDeleted = isDeleted.toSealedFlag(),
        authorUuid = authorUuid,
        signature = Base64.Default.encode(signature),
    )

    return sealBound(
        key = groupKey,
        context = contextOf(groupUuid = groupUuid, expenseUuid = uuid),
        plaintext = json.encodeToString(SealedGroupExpense.serializer(), content).encodeToByteArray(),
    )
}

/**
 * Opens this content, as the version [revision] of the expense [expenseUuid] of the group
 * [groupUuid], with [groupKey], the group's key of the generation the server lists it under, or
 * returns null when it does not open as that expense's under that key, does not read as a version,
 * or holds a UUID not written the one way the server and the app write one: these reach the
 * library, which throws on a string that is not valid UTF-16 rather than refusing it, as does any
 * other string that is not, which JSON can carry.
 *
 * The version is rebuilt from [groupUuid], [expenseUuid] and [revision], which the signature covers:
 * one served at another revision than its author signed, or moved to another expense, opens and
 * then does not verify, see [isSignedBy]. Neither who signed it nor what it says is checked here.
 */
internal fun EncryptedFieldDto.unsealGroupExpense(
    groupKey: ByteArray,
    groupUuid: String,
    expenseUuid: String,
    revision: Long,
): SignedGroupExpense? {
    if (revision < FIRST_GROUP_EXPENSE_REVISION) return null

    val plaintext = unsealBound(key = groupKey, context = contextOf(groupUuid = groupUuid, expenseUuid = expenseUuid), content = this)
        ?: return null

    val content = runCatching {
        json.decodeFromString(SealedGroupExpense.serializer(), plaintext.decodeToString())
    }.getOrNull() ?: return null

    val uuids = listOf(content.authorUuid, content.creditorUuid) + content.beneficiaries.map { it.userUuid }

    if (!uuids.all { it.isCanonicalUuid() }) return null

    val texts = listOf(content.title, content.description, content.amount, content.timestamp) +
        content.beneficiaries.map { it.share }

    if (!texts.all { it.isWellFormed() }) return null

    val signature = runCatching { Base64.Default.decode(content.signature) }.getOrNull() ?: return null

    return SignedGroupExpense(
        version = GroupExpenseVersion(
            groupUuid = groupUuid,
            expenseUuid = expenseUuid,
            revision = revision.toULong(),
            title = content.title,
            description = content.description,
            amount = content.amount,
            timestamp = content.timestamp,
            creditorUuid = content.creditorUuid,
            beneficiaries = content.beneficiaries.map { beneficiary ->
                GroupExpenseBeneficiary(
                    userUuid = beneficiary.userUuid,
                    share = beneficiary.share,
                    isShareFixed = beneficiary.isShareFixed.toFlagOrNull() ?: return null,
                )
            },
            isSettlement = content.isSettlement.toFlagOrNull() ?: return null,
            isDeleted = content.isDeleted.toFlagOrNull() ?: return null,
        ),
        authorUuid = content.authorUuid,
        signature = signature,
    )
}

/**
 * Whether its author signed this version with the private half of [authorKey], the key
 * [authorKeyOf] reads for them, and it is a version they may sign: a settlement, only when they are
 * the member it was paid to, its one beneficiary, see `group_expense` in lib-duckcount.
 *
 * An author this device holds no key for, [authorKey] null, is taken at the version's word, as a
 * group's creator is for its information, see [isSignedByCreator]: there is nothing to hold it
 * against. That is a member who left before the user joined the group, whose leaf this device never
 * read there nor in any other group of the same creator's, or any member of a group brought back
 * after a log-out who is not one of the user's contacts.
 * Only a holder of the group's key can have sealed it, so the server cannot make one up; a member
 * can then pass a version off as another's, which they can anyway to every device that never read
 * that other's leaf. Who may sign a settlement holds all the same, which is no signature's to tell.
 */
internal fun SignedGroupExpense.isSignedBy(authorKey: ByteArray?): Boolean =
    if (authorKey == null) {
        !version.isSettlement || version.beneficiaries.singleOrNull()?.userUuid == authorUuid
    } else {
        verifyGroupExpense(
            version = version,
            authorUuid = authorUuid,
            authorSignatureKey = authorKey,
            signature = signature,
        )
    }

/**
 * This version, in [currency], the group's, see [OpenedGroupExpense], or null when what it says is
 * not what the app writes: a title or description longer than the form allows, a timestamp not
 * written exactly as the app writes one, or an amount or shares [validateExpense] refuses, which
 * also refuses a beneficiary listed twice, or more than an expense may list. The screens read these
 * values as the app writes them, and the debt algorithm refuses a group holding an expense whose
 * shares do not add up to its amount: one such version would leave the group with no balances.
 */
internal fun SignedGroupExpense.opened(currency: Currency): OpenedGroupExpense? {
    if (version.title.length > MAX_TITLE_LENGTH) return null
    if (version.description.length > MAX_DESCRIPTION_LENGTH) return null
    if (!version.timestamp.isCanonicalTimestamp()) return null

    val expense = ExpenseWithBeneficiaries(
        uuid = version.expenseUuid,
        title = version.title,
        timestamp = version.timestamp,
        description = version.description,
        amount = version.amount,
        creditorUuid = version.creditorUuid,
        isSettlement = version.isSettlement,
        currency = currency,
        groupUuid = version.groupUuid,
        isDeleted = version.isDeleted,
        beneficiaries = version.beneficiaries.map { beneficiary ->
            Beneficiary(
                expenseUuid = version.expenseUuid,
                userUuid = beneficiary.userUuid,
                share = beneficiary.share,
                isShareFixed = beneficiary.isShareFixed,
            )
        },
    )

    val error = validateExpense(amount = expense.amount, currency = currency, beneficiaries = expense.beneficiaries)

    if (error != null) return null

    return OpenedGroupExpense(expense = expense, revision = version.revision.toLong(), authorUuid = authorUuid)
}

/**
 * The MLS signature key the versions [authorUuid] writes in the group [groupUuid] are checked
 * against, see [SignedGroupExpense.isSignedBy], the first of these this device holds: the user's own,
 * when [authorUuid] is the user, [selfUuid]; the one pinned for them, a contact of the user's, whose
 * identity this device holds them to everywhere; and the one the group's tree held for them when this
 * device last read it there, kept once they left it, or else another group of the same creator's,
 * see [recorded]. Or null when it holds none.
 *
 * Throws [MlsException] when the pin could not be read, which is not there being none: taken for
 * none, a contact's version would be checked against a key the server could have served.
 */
internal suspend fun MlsClient.authorKeyOf(
    authorUuid: String,
    selfUuid: String,
    groupUuid: String,
    recorded: suspend (groupUuid: String, userUuid: String) -> ByteArray?,
): ByteArray? =
    if (authorUuid == selfUuid) {
        signaturePublicKey()
    } else {
        pinnedSignatureKeyFor(peerUuid = authorUuid) ?: recorded(groupUuid, authorUuid)
    }

/**
 * Whether [next], a later version of this group expense, may replace this one: both are a
 * settlement, or neither is, and a settlement only goes from shown to deleted, saying the rest as
 * it did, and nothing comes after that, see `group_expense` in lib-duckcount.
 *
 * Anybody may write any version of an expense but a settlement's, which its recipient alone signs,
 * see [SignedGroupExpense.isSignedBy]: they record it, and may take it back, but neither edit it,
 * which would change what was paid back after the fact, nor restore it once deleted, which another
 * settlement records instead. Neither can a version make an expense a settlement, which its creator
 * did not write as one, nor a settlement an expense, both being what the next version is next to.
 */
internal fun ExpenseWithBeneficiariesEntity.mayBeFollowedBy(next: ExpenseWithBeneficiariesEntity): Boolean =
    when {
        next.expense.isSettlement != expense.isSettlement -> false
        !expense.isSettlement -> true
        else -> !expense.isDeleted && next.expense.isDeleted && next.saysWhat(this)
    }

/**
 * Whether this version of an expense says what [other] does, whether it is deleted aside: its
 * title and description, amount and date, creditor and beneficiaries with their shares, in
 * whatever order, and whether it is a settlement.
 */
internal fun ExpenseWithBeneficiariesEntity.saysWhat(other: ExpenseWithBeneficiariesEntity): Boolean =
    expense.title == other.expense.title &&
        expense.description == other.expense.description &&
        expense.amount == other.expense.amount &&
        expense.timestamp == other.expense.timestamp &&
        expense.creditorUuid == other.expense.creditorUuid &&
        expense.isSettlement == other.expense.isSettlement &&
        beneficiaries.map { Triple(it.userUuid, it.share, it.isShareFixed) }.toSet() ==
        other.beneficiaries.map { Triple(it.userUuid, it.share, it.isShareFixed) }.toSet()

/** What becomes of a version of a group expense this device tries to open, see [GroupExpenseOpener]. */
internal sealed interface GroupExpenseOpening {

    /** It opened, its author signed it, and it says what the app writes. */
    class Opened(val version: OpenedGroupExpense) : GroupExpenseOpening

    /**
     * It is sealed under a generation of the group's key this device cannot have yet, see
     * [GroupKeyChain]: kept for later, see [database.data.local.entity.UnreadableGroupExpenseEntity].
     */
    data object Unreadable : GroupExpenseOpening

    /**
     * It does not open as that expense's under the key it was listed under, nobody this device holds
     * a key for signed it, or what it says is not what the app writes: never shown.
     */
    data object Refused : GroupExpenseOpening

    /**
     * What it takes to tell could not be read this time: the server's links to the key it is sealed
     * under, or the pin of its author, see [GroupExpenseOpener.failure]. Tried again by the next pass.
     */
    data object Failed : GroupExpenseOpening
}

/**
 * Opens versions of the expenses of [group], one after the other, for a pass over them, see
 * [GroupExpenseSync] and [GroupExpenseHistory]: the key of each generation through [keyOf], see
 * [GroupKeyChain.keyOf], and the key of each author through [authorKeyOf], each read once for the
 * pass, whatever came of it, so that a group of a thousand expenses asks the server for its links
 * once at most for each generation this device lacks, and reads each pin once.
 *
 * [selfUuid] is the user's, [client] their MLS identity, and [recorded] is
 * [database.data.local.dao.GroupDao.getMemberSignatureKey], or what a test stands in for either.
 */
internal class GroupExpenseOpener(
    private val group: GroupEntity,
    private val selfUuid: String,
    private val client: MlsClient,
    private val keyOf: suspend (groupUuid: String, generation: Long) -> Result<ByteArray, GroupKeyChainError>,
    private val recorded: suspend (groupUuid: String, userUuid: String) -> ByteArray?,
) {
    private val keys = mutableMapOf<Long, Result<ByteArray, GroupKeyChainError>>()

    private val authorKeys = mutableMapOf<String, ByteArray?>()

    /**
     * What kept the first version that [GroupExpenseOpening.Failed] from being told, or null: the
     * server could not be asked for the links, or the user's storage failed as a pin was read.
     */
    var failure: Failure? = null
        private set

    /**
     * What becomes of the version [revision] of the expense [expenseUuid] of the group, [content]
     * sealed under its key of [keyGeneration], see [GroupExpenseOpening].
     */
    suspend fun open(expenseUuid: String, revision: Long, keyGeneration: Long, content: EncryptedFieldDto): GroupExpenseOpening {
        val key = when (val read = keys.getOrPut(keyGeneration) { keyOf(group.uuid, keyGeneration) }) {
            is Result.Success -> read.data

            is Result.Error -> return when (val error = read.error) {
                is GroupKeyChainError.Network -> {
                    failure = failure ?: Failure.Network(error.error)

                    GroupExpenseOpening.Failed
                }

                GroupKeyChainError.NotHeld,
                GroupKeyChainError.NotMember,
                GroupKeyChainError.Unreadable,
                -> GroupExpenseOpening.Unreadable
            }
        }

        val signed = content.unsealGroupExpense(
            groupKey = key,
            groupUuid = group.uuid,
            expenseUuid = expenseUuid,
            revision = revision,
        ) ?: return GroupExpenseOpening.Refused

        val authorKey = if (signed.authorUuid in authorKeys) {
            authorKeys[signed.authorUuid]
        } else {
            try {
                client.authorKeyOf(
                    authorUuid = signed.authorUuid,
                    selfUuid = selfUuid,
                    groupUuid = group.uuid,
                    recorded = recorded,
                ).also { authorKeys[signed.authorUuid] = it }
            } catch (_: MlsException) {
                failure = failure ?: Failure.Storage

                return GroupExpenseOpening.Failed
            }
        }

        if (!signed.isSignedBy(authorKey = authorKey)) return GroupExpenseOpening.Refused

        return signed.opened(currency = group.currency)?.let { GroupExpenseOpening.Opened(version = it) }
            ?: GroupExpenseOpening.Refused
    }

    /** Why a version could not be told, see [failure]. */
    sealed interface Failure {

        /** The server could not be asked for the links of the chain back through the group's keys. */
        data class Network(val error: NetworkError) : Failure

        /** The user's own storage failed as the key pinned for a member was read. */
        data object Storage : Failure
    }
}

/**
 * This expense as [revision] of the group [groupUuid]: what its author signs, see `group_expense`
 * in lib-duckcount.
 */
private fun ExpenseWithBeneficiaries.versionOf(groupUuid: String, revision: Long) = GroupExpenseVersion(
    groupUuid = groupUuid,
    expenseUuid = uuid,
    revision = revision.toULong(),
    title = title,
    description = description,
    amount = amount,
    timestamp = timestamp,
    creditorUuid = creditorUuid,
    beneficiaries = beneficiaries.map { beneficiary ->
        GroupExpenseBeneficiary(
            userUuid = beneficiary.userUuid,
            share = beneficiary.share,
            isShareFixed = beneficiary.isShareFixed,
        )
    },
    isSettlement = isSettlement,
    isDeleted = isDeleted,
)

/**
 * The one sealed field of a version of a group expense, as JSON: everything it says, who wrote it,
 * and their signature over all of it, in standard Base64, see [sealGroupExpense].
 */
@Serializable
private class SealedGroupExpense(
    val title: String,
    val description: String,
    val amount: String,
    val timestamp: String,

    @SerialName("creditor_uuid")
    val creditorUuid: String,

    val beneficiaries: List<SealedGroupExpenseBeneficiary>,

    /** A flag sealed as [toSealedFlag] seals one. */
    @SerialName("is_settlement")
    val isSettlement: Int,

    /** A flag sealed as [toSealedFlag] seals one. */
    @SerialName("is_deleted")
    val isDeleted: Int,

    @SerialName("author_uuid")
    val authorUuid: String,

    val signature: String,
)

/** One beneficiary of a [SealedGroupExpense], with their share. */
@Serializable
private class SealedGroupExpenseBeneficiary(
    @SerialName("user_uuid")
    val userUuid: String,

    val share: String,

    /** A flag sealed as [toSealedFlag] seals one. */
    @SerialName("is_share_fixed")
    val isShareFixed: Int,
)

/**
 * A flag as a version seals it: `1` or `0`, and not `true` or `false`, which differ in length, as a
 * one-time expense seals its own, see `expense.OneTimeExpenseSealing`. AES-GCM does not hide how long
 * what it seals is, and a deletion, or a restore, saying the rest as the version before did, would
 * otherwise tell the server what it was by the length of the version alone.
 */
private fun Boolean.toSealedFlag(): Int = if (this) 1 else 0

/** The flag [toSealedFlag] sealed, or null for anything but `1` or `0`. */
private fun Int.toFlagOrNull(): Boolean? =
    when (this) {
        1 -> true
        0 -> false
        else -> null
    }

/** Whether this string is valid UTF-16, every surrogate paired, as the library takes no other. */
private fun String.isWellFormed(): Boolean {
    var index = 0

    while (index < length) {
        val char = this[index]

        when {
            char.isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false

                index += 2
            }

            char.isLowSurrogate() -> return false

            else -> index++
        }
    }

    return true
}
