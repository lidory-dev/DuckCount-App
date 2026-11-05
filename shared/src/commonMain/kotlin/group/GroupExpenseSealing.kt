package group

import app.lidory.duckcount.ExpenseLimits.MAX_DESCRIPTION_LENGTH
import app.lidory.duckcount.ExpenseLimits.MAX_GROUP_EXPENSE_EDITS
import app.lidory.duckcount.ExpenseLimits.MAX_TITLE_LENGTH
import app.lidory.duckcount.validateExpense
import database.data.local.entity.ExpenseWithBeneficiariesEntity
import database.data.local.entity.GroupEntity
import database.data.local.entity.GroupExpenseDeletionEntity
import database.data.mappers.toExpenseWithBeneficiaries
import database.data.mappers.toExpenseWithBeneficiariesEntity
import database.data.remote.EncryptedFieldDto
import database.domain.Beneficiary
import database.domain.Currency
import database.domain.ExpenseWithBeneficiaries
import expense.isCanonicalTimestamp
import expense.isCanonicalUuid
import expense.isSealedAmount
import expense.toSealedAmount
import expense.toSealedShareSetting
import expense.toShareSettingOrNull
import expense.withoutAmountPadding
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import mls.pinnedSignatureKeyFor
import uniffi.lib_duckcount.GroupExpenseBeneficiary
import uniffi.lib_duckcount.GroupExpenseDeletion
import uniffi.lib_duckcount.GroupExpenseVersion
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsException
import uniffi.lib_duckcount.verifyGroupExpense
import uniffi.lib_duckcount.verifyGroupExpenseDeletion
import util.NetworkError
import util.Result
import vault.sealBound
import vault.unsealBound
import kotlin.io.encoding.Base64

/** The revision a group expense is created at, see [GroupExpenseVersion]. Each edit since adds one. */
internal const val FIRST_GROUP_EXPENSE_REVISION = 1L

/**
 * The revision a group expense stands at once it had every edit it takes, see
 * [MAX_GROUP_EXPENSE_EDITS]: no version past it is ever sent, nor shown, whoever signed it, see
 * [unsealGroupExpense].
 */
internal val LAST_GROUP_EXPENSE_REVISION = FIRST_GROUP_EXPENSE_REVISION + MAX_GROUP_EXPENSE_EDITS

/** The revision of an expense's first deletion flag, see [GroupExpenseDeletion]. Each change since adds one. */
internal const val FIRST_GROUP_EXPENSE_DELETION_REVISION = 1L

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

/**
 * What a deletion flag of the expense [expenseUuid] of the group [groupUuid] is bound to, as a
 * version is, see [contextOf], and apart from it: neither opens as the other.
 */
private fun deletionContextOf(groupUuid: String, expenseUuid: String) = "group_expense_deletion:$groupUuid:$expenseUuid"

/** One version of a group expense, opened and checked, see [GroupExpenseOpener]. */
internal class OpenedGroupExpense(
    /**
     * What it says, in the group's currency, its group set. Never deleted: whether the expense is
     * is no part of a version, see [GroupExpenseDeletionEntity].
     */
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

    /**
     * Whether its title, amount and shares were sealed at one length, see [toSealedTitle] and
     * [toSealedAmount], which nothing in [version] says, each being signed as it is written, see
     * [opened].
     */
    val isSealedAtOneLength: Boolean,
)

/**
 * A deletion flag of a group expense unsealed, see [openGroupExpenseDeletion]: what its author
 * signed, as the library takes it, who it says they are, and the signature, none of it checked yet.
 */
internal class SignedGroupExpenseDeletion(
    val deletion: GroupExpenseDeletion,
    val authorUuid: String,
    val signature: ByteArray,
)

/**
 * Seals this expense, of the group [groupUuid], under [groupKey], the group's key of the generation
 * it is sent with, as [revision], signed by [client], the user [authorUuid]'s MLS identity: the
 * `content` of [CreateGroupExpenseBody] and [UpdateGroupExpenseBody].
 *
 * One sealed field holds it all, its title and description, amount and date, creditor and
 * beneficiaries, and whether it is a settlement, with its author and their signature, see
 * [SealedGroupExpense]: the server learns nothing of who paid or who benefits, nor who wrote the
 * version. The group, the expense and the revision are not in it: the route names the first two and
 * the server lists the third, and the signature holds every one of them to what the author signed,
 * see [unsealGroupExpense]. Nor is whether it is deleted, which its deletion flag says apart, see
 * [sealGroupExpenseDeletion].
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
        title = title.toSealedTitle(),
        description = description,
        amount = amount.toSealedAmount(),
        timestamp = timestamp,
        creditorUuid = creditorUuid,
        beneficiaries = beneficiaries.map { beneficiary ->
            SealedGroupExpenseBeneficiary(
                userUuid = beneficiary.userUuid,
                share = beneficiary.share.toSealedAmount(),
                isShareFixed = beneficiary.isShareFixed.toSealedFlag(),
                sharePercentage = beneficiary.sharePercentage.toSealedShareSetting(),
                shareWeight = beneficiary.shareWeight.toSealedShareSetting(),
            )
        },
        isSettlement = isSettlement.toSealedFlag(),
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
 * other string that is not, which JSON can carry. Null too for a revision before the first or past
 * the last, see [LAST_GROUP_EXPENSE_REVISION], which the library refuses whoever signed it, and a
 * version whose author this device holds no key for never reaches the library to be refused by,
 * see [isSignedBy].
 *
 * The version is rebuilt from [groupUuid], [expenseUuid] and [revision], which the signature covers:
 * one served at another revision than its author signed, or moved to another expense, opens and
 * then does not verify, see [isSignedBy]. Neither who signed it, nor what it says, nor whether it
 * may say it at its revision, a settlement past its first, nor whether its title and amounts were
 * sealed at one length, is checked here: it opens all the same, so that the key it opens under is told from
 * any other the chain of links leads to, see [GroupKeyChain], and then is refused, rather than taken
 * for one sealed under a key not held yet.
 */
internal fun EncryptedFieldDto.unsealGroupExpense(
    groupKey: ByteArray,
    groupUuid: String,
    expenseUuid: String,
    revision: Long,
): SignedGroupExpense? {
    if (revision !in FIRST_GROUP_EXPENSE_REVISION..LAST_GROUP_EXPENSE_REVISION) return null

    val plaintext = unsealBound(key = groupKey, context = contextOf(groupUuid = groupUuid, expenseUuid = expenseUuid), content = this)
        ?: return null

    val content = runCatching {
        json.decodeFromString(SealedGroupExpense.serializer(), plaintext.decodeToString())
    }.getOrNull() ?: return null

    val uuids = listOf(content.authorUuid, content.creditorUuid) + content.beneficiaries.map { it.userUuid }

    if (!uuids.all { it.isCanonicalUuid() }) return null

    val texts = listOf(content.title, content.description, content.amount, content.timestamp) +
        content.beneficiaries.flatMap { listOf(it.share, it.sharePercentage, it.shareWeight) }

    if (!texts.all { it.isWellFormed() }) return null

    val signature = runCatching { Base64.Default.decode(content.signature) }.getOrNull() ?: return null

    val isSettlement = content.isSettlement.toFlagOrNull() ?: return null

    return SignedGroupExpense(
        version = GroupExpenseVersion(
            groupUuid = groupUuid,
            expenseUuid = expenseUuid,
            revision = revision.toULong(),
            title = content.title.trimEnd(' '),
            description = content.description,
            amount = content.amount.withoutAmountPadding(),
            timestamp = content.timestamp,
            creditorUuid = content.creditorUuid,
            beneficiaries = content.beneficiaries.map { beneficiary ->
                GroupExpenseBeneficiary(
                    userUuid = beneficiary.userUuid,
                    share = beneficiary.share.withoutAmountPadding(),
                    isShareFixed = beneficiary.isShareFixed.toFlagOrNull() ?: return null,
                    sharePercentage = beneficiary.sharePercentage.toShareSettingOrNull(),
                    shareWeight = beneficiary.shareWeight.toShareSettingOrNull(),
                )
            },
            isSettlement = isSettlement,
        ),
        authorUuid = content.authorUuid,
        signature = signature,
        isSealedAtOneLength = content.title.isSealedTitle() &&
            (listOf(content.amount) + content.beneficiaries.map { it.share }).all { it.isSealedAmount() },
    )
}

/**
 * Whether its author signed this version with the private half of [authorKey], the key
 * [authorKeyOf] reads for them, and it is a version they may sign: a settlement, only at the first
 * revision, see [FIRST_GROUP_EXPENSE_REVISION], and only when they are the member it was paid to,
 * its one beneficiary, see `group_expense` in lib-duckcount.
 *
 * Nobody edits a settlement, which would change what was paid back after the fact, and deleting
 * it is no version but its deletion flag, see [SignedGroupExpenseDeletion.isSignedBy]: its first
 * version is its only one, and one past it is an edit by its revision alone, told without the
 * version before it, as is one making an expense a settlement, which its creator did not write as
 * one. So a device that never showed the first, its member having joined the group since or logged
 * out, refuses it as one that did, and shows nothing of the settlement, the server listing only the
 * version it stands at, rather than what its recipient rewrote it into. That is checked first,
 * whatever key the author is held to, as the library checks it first.
 *
 * An author this device holds no key for, [authorKey] null, is taken at the version's word, as a
 * group's creator is for its information, see [isSignedByCreator]: there is nothing to hold it
 * against. That is a member who left before the user joined the group, whose leaf this device never
 * read there nor in any other group of the same creator's, or any member of a group brought back
 * after a log-out who is not one of the user's contacts.
 * Only a holder of the group's key can have sealed it, so the server cannot make one up; a member
 * can then pass a version off as another's, which they can anyway to every device that never read
 * that other's leaf. Who may sign a settlement, and at which revision, holds all the same, which is
 * no signature's to tell.
 */
internal fun SignedGroupExpense.isSignedBy(authorKey: ByteArray?): Boolean {
    if (version.isSettlement && version.revision != FIRST_GROUP_EXPENSE_REVISION.toULong()) return false

    return if (authorKey == null) {
        !version.isSettlement || version.beneficiaries.singleOrNull()?.userUuid == authorUuid
    } else {
        verifyGroupExpense(
            version = version,
            authorUuid = authorUuid,
            authorSignatureKey = authorKey,
            signature = signature,
        )
    }
}

/**
 * This version, in [currency], the group's, see [OpenedGroupExpense], or null when what it says is
 * not what the app writes: a title or description longer than the form allows, a timestamp not
 * written exactly as the app writes one, a title, amount or shares not sealed at one length, see
 * [toSealedTitle] and [toSealedAmount], or an amount or shares [validateExpense] refuses, which also refuses a
 * beneficiary listed twice, or more than an expense may list. The screens read these
 * values as the app writes them, and the debt algorithm refuses a group holding an expense whose
 * shares do not add up to its amount: one such version would leave the group with no balances.
 */
internal fun SignedGroupExpense.opened(currency: Currency): OpenedGroupExpense? {
    if (!isSealedAtOneLength) return null
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
        beneficiaries = version.beneficiaries.map { beneficiary ->
            Beneficiary(
                expenseUuid = version.expenseUuid,
                userUuid = beneficiary.userUuid,
                share = beneficiary.share,
                isShareFixed = beneficiary.isShareFixed,
                sharePercentage = beneficiary.sharePercentage,
                shareWeight = beneficiary.shareWeight,
            )
        },
    )

    val error = validateExpense(amount = expense.amount, currency = currency, beneficiaries = expense.beneficiaries)

    if (error != null) return null

    return OpenedGroupExpense(expense = expense, revision = version.revision.toLong(), authorUuid = authorUuid)
}

/**
 * The MLS signature key the versions and the deletion flags [authorUuid] writes in the group
 * [groupUuid] are checked against, see [SignedGroupExpense.isSignedBy], the first of these this
 * device holds: the user's own, when [authorUuid] is the user, [selfUuid]; the one pinned for them,
 * a contact of the user's, whose identity this device holds them to everywhere; and the one the
 * group's tree held for them when this device last read it there, kept once they left it, or else
 * another group of the same creator's, see [recorded]. Or null when it holds none.
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
 * Whether a later version of this group expense may replace this one: it is no settlement, see
 * `group_expense` in lib-duckcount.
 *
 * Anybody may write any version of an expense but a settlement's, which its recipient alone signs,
 * see [SignedGroupExpense.isSignedBy]: they record it, and nobody edits it, which would change what
 * was paid back after the fact. Deleting it is no version, but its recipient's deletion flag, see
 * [SignedGroupExpenseDeletion.isSignedBy], which nobody restores it with, another settlement
 * recording it again instead.
 *
 * A later version that is a settlement itself, an edit of one or one making an expense a
 * settlement, never comes this far: it is past the first revision, and so refused as it is opened,
 * whoever signed it, on a device that never showed the first as on one that did, see
 * [SignedGroupExpense.isSignedBy], and the user's own edits are never one, see
 * [GroupExpenseWriter.edit]. What only a device that showed the settlement refuses, here, is a
 * later version that is no settlement, an edit of it into an expense, which no version alone tells
 * from one written as an expense: a device that never showed the settlement shows the expense it
 * says.
 */
internal fun ExpenseWithBeneficiariesEntity.mayBeFollowed(): Boolean = !expense.isSettlement

/**
 * Whether this version of an expense says what [other] does, whether it is deleted aside, which is
 * no part of a version: its title and description, amount and date, creditor and beneficiaries
 * with their shares, in whatever order, and whether it is a settlement.
 */
internal fun ExpenseWithBeneficiariesEntity.saysWhat(other: ExpenseWithBeneficiariesEntity): Boolean =
    expense.title == other.expense.title &&
        expense.description == other.expense.description &&
        expense.amount == other.expense.amount &&
        expense.timestamp == other.expense.timestamp &&
        expense.creditorUuid == other.expense.creditorUuid &&
        expense.isSettlement == other.expense.isSettlement &&
        beneficiaries.map { listOf(it.userUuid, it.share, it.isShareFixed, it.sharePercentage, it.shareWeight) }.toSet() ==
        other.beneficiaries.map { listOf(it.userUuid, it.share, it.isShareFixed, it.sharePercentage, it.shareWeight) }.toSet()

/**
 * Seals the deletion flag saying whether the expense [expenseUuid] of the group [groupUuid] is
 * deleted, [isDeleted], at [deletionRevision], under [groupKey], the group's key of the generation
 * it is sent with, signed by [client], the user [authorUuid]'s MLS identity: the `content` of
 * [SetGroupExpenseDeletionBody].
 *
 * Apart from the expense's versions: deleting it and restoring it are no edit, use up none of the
 * edits it takes, and leave its versions as they are. So the flag is signed apart too, under a
 * label of its own, over the group, the expense, [deletionRevision] and [isDeleted], see
 * `group_expense` in lib-duckcount, and sealed apart, see [deletionContextOf], whether it is
 * deleted, its author and their signature in it, see [SealedGroupExpenseDeletion]: the server
 * learns who deleted it no more than who wrote a version.
 *
 * Returns null only when [groupKey] is not a 32-byte key or the library refuses to sign, both caller
 * bugs rather than states, see [sealGroupExpense].
 */
internal fun sealGroupExpenseDeletion(
    groupUuid: String,
    expenseUuid: String,
    deletionRevision: Long,
    isDeleted: Boolean,
    groupKey: ByteArray,
    client: MlsClient,
    authorUuid: String,
): EncryptedFieldDto? {
    val signature = try {
        client.signGroupExpenseDeletion(
            deletion = GroupExpenseDeletion(
                groupUuid = groupUuid,
                expenseUuid = expenseUuid,
                deletionRevision = deletionRevision.toULong(),
                isDeleted = isDeleted,
            ),
        )
    } catch (_: MlsException) {
        return null
    }

    val content = SealedGroupExpenseDeletion(
        isDeleted = isDeleted.toSealedFlag(),
        authorUuid = authorUuid,
        signature = Base64.Default.encode(signature),
    )

    return sealBound(
        key = groupKey,
        context = deletionContextOf(groupUuid = groupUuid, expenseUuid = expenseUuid),
        plaintext = json.encodeToString(SealedGroupExpenseDeletion.serializer(), content).encodeToByteArray(),
    )
}

/**
 * Opens this content, as the deletion flag of the expense [expenseUuid] of the group [groupUuid] at
 * [deletionRevision], with [groupKey], the group's key of the generation the server lists it under,
 * or returns null when it does not open as that expense's flag under that key, does not read as a
 * flag, names its author in a UUID not written the one way the server and the app write one, see
 * [unsealGroupExpense], or is at a revision before the first, see
 * [FIRST_GROUP_EXPENSE_DELETION_REVISION], which the library refuses whoever signed it.
 *
 * The flag is rebuilt from [groupUuid], [expenseUuid] and [deletionRevision], which the signature
 * covers, as a version is: neither who signed it nor whether they may is checked here, see
 * [SignedGroupExpenseDeletion.isSignedBy].
 */
internal fun EncryptedFieldDto.openGroupExpenseDeletion(
    groupKey: ByteArray,
    groupUuid: String,
    expenseUuid: String,
    deletionRevision: Long,
): SignedGroupExpenseDeletion? {
    if (deletionRevision < FIRST_GROUP_EXPENSE_DELETION_REVISION) return null

    val plaintext = unsealBound(
        key = groupKey,
        context = deletionContextOf(groupUuid = groupUuid, expenseUuid = expenseUuid),
        content = this,
    ) ?: return null

    val content = runCatching {
        json.decodeFromString(SealedGroupExpenseDeletion.serializer(), plaintext.decodeToString())
    }.getOrNull() ?: return null

    if (!content.authorUuid.isCanonicalUuid()) return null

    val signature = runCatching { Base64.Default.decode(content.signature) }.getOrNull() ?: return null

    return SignedGroupExpenseDeletion(
        deletion = GroupExpenseDeletion(
            groupUuid = groupUuid,
            expenseUuid = expenseUuid,
            deletionRevision = deletionRevision.toULong(),
            isDeleted = content.isDeleted.toFlagOrNull() ?: return null,
        ),
        authorUuid = content.authorUuid,
        signature = signature,
    )
}

/**
 * Whether its author signed this flag with the private half of [authorKey], the key [authorKeyOf]
 * reads for them, and it is a flag they may sign of the expense [shown], the version of it the flag
 * is checked against: the one this device shows or, of an expense it shows no version of yet, the
 * one listed with the flag, which is stored only together with it, see [GroupExpenseSync]. Of a
 * settlement, only one deleting it, and only when they are the member it was paid to, see
 * `group_expense` in lib-duckcount. Nobody restores a settlement, which another settlement records
 * again instead.
 *
 * An author this device holds no key for, [authorKey] null, is taken at the flag's word, as for a
 * version, see [SignedGroupExpense.isSignedBy], who may sign one of a settlement holding all the
 * same.
 */
internal fun SignedGroupExpenseDeletion.isSignedBy(authorKey: ByteArray?, shown: ExpenseWithBeneficiariesEntity): Boolean {
    val version = shown.toExpenseWithBeneficiaries().versionOf(
        groupUuid = checkNotNull(shown.expense.groupUuid),
        revision = checkNotNull(shown.expense.revision),
    )

    return if (authorKey == null) {
        !version.isSettlement || (deletion.isDeleted && version.beneficiaries.singleOrNull()?.userUuid == authorUuid)
    } else {
        verifyGroupExpenseDeletion(
            deletion = deletion,
            expense = version,
            authorUuid = authorUuid,
            authorSignatureKey = authorKey,
            signature = signature,
        )
    }
}

/**
 * What becomes of a version of a group expense, or of its deletion flag, this device tries to open,
 * see [GroupExpenseOpener], [T] being what one opens to.
 */
internal sealed interface GroupExpenseOpening<out T> {

    /** It opened, its author signed it, and it says what the app writes, or for a flag, what they may. */
    class Opened<out T>(val opened: T) : GroupExpenseOpening<T>

    /**
     * It is sealed under a generation of the group's key this device cannot have yet, see
     * [GroupKeyChain], or ever, as one sealed in an epoch the creator's replacement of a Commit
     * undid, see [ReplaceGroupCommitBody], whose key no member holds: kept for later, see
     * [database.data.local.entity.UnreadableGroupExpenseEntity], and no failure of the pass.
     */
    data object Unreadable : GroupExpenseOpening<Nothing>

    /**
     * It does not open as that expense's under the key it was listed under, nobody this device holds
     * a key for signed it, or what it says is not what the app writes, or for a flag, what its
     * author may: never shown.
     */
    data object Refused : GroupExpenseOpening<Nothing>

    /**
     * What it takes to tell could not be read this time: the server's links to the key it is sealed
     * under, or the pin of its author, see [GroupExpenseOpener.failure]. Tried again by the next pass.
     */
    data object Failed : GroupExpenseOpening<Nothing>
}

/**
 * Opens versions of the expenses of [group], and their deletion flags, one after the other, for a
 * pass over them, see [GroupExpenseSync] and [GroupExpenseHistory]: the key of each generation
 * through [keyOf], see [GroupKeyChain.keyOf], and the key of each author through [authorKeyOf],
 * each read once for the pass, whatever came of it, but for a version or a flag that opens under no
 * key its generation gave so far, see [open], so that a group of a thousand expenses asks the server
 * for its links once for each generation this device lacks, and again only for each key a version of
 * a member's own leads to, and reads each pin once.
 *
 * [selfUuid] is the user's, [client] their MLS identity, and [recorded] is
 * [database.data.local.dao.GroupDao.getMemberSignatureKey], or what a test stands in for either.
 */
internal class GroupExpenseOpener(
    private val group: GroupEntity,
    private val selfUuid: String,
    private val client: MlsClient,
    private val keyOf: suspend (
        groupUuid: String,
        generation: Long,
        opens: (key: ByteArray) -> Boolean,
    ) -> Result<ByteArray, GroupKeyChainError>,
    private val recorded: suspend (groupUuid: String, userUuid: String) -> ByteArray?,
) {
    /** The keys of each generation found in this pass, the first version of it read telling the first. */
    private val keys = mutableMapOf<Long, MutableList<ByteArray>>()

    /**
     * Each generation whose key was asked for once more in this pass and came to nothing new, see
     * [open], with what kept it from coming to anything, if anything did: asked for no more.
     */
    private val settled = mutableMapOf<Long, GroupKeyChainError?>()

    private val authorKeys = mutableMapOf<String, ByteArray?>()

    /**
     * What kept the first version or flag that [GroupExpenseOpening.Failed] from being told, or
     * null: the server could not be asked for the links, or the user's storage failed as a pin was
     * read.
     */
    var failure: Failure? = null
        private set

    /**
     * What becomes of the version [revision] of the expense [expenseUuid] of the group, [content]
     * sealed under its key of [keyGeneration], see [GroupExpenseOpening].
     */
    suspend fun open(
        expenseUuid: String,
        revision: Long,
        keyGeneration: Long,
        content: EncryptedFieldDto,
    ): GroupExpenseOpening<OpenedGroupExpense> {
        fun unsealed(key: ByteArray) = content.unsealGroupExpense(
            groupKey = key,
            groupUuid = group.uuid,
            expenseUuid = expenseUuid,
            revision = revision,
        )

        val key = keyFor(keyGeneration = keyGeneration, opens = { unsealed(it) != null }) { return it }
        val signed = unsealed(key) ?: return GroupExpenseOpening.Refused
        val authorKey = authorKeyFor(authorUuid = signed.authorUuid) { return it }

        if (!signed.isSignedBy(authorKey = authorKey)) return GroupExpenseOpening.Refused

        return signed.opened(currency = group.currency)?.let { GroupExpenseOpening.Opened(opened = it) }
            ?: GroupExpenseOpening.Refused
    }

    /**
     * What becomes of the deletion flag of the expense [expenseUuid] of the group at
     * [deletionRevision], [content] sealed under its key of [keyGeneration], checked against
     * [shown], a version of that expense: the one this device shows or, of an expense it shows no
     * version of yet, the one listed with the flag, which is stored only together with it, see
     * [GroupExpenseSync] and [SignedGroupExpenseDeletion.isSignedBy]. Opened, it is the flag as it
     * is stored, see [GroupExpenseDeletionEntity].
     */
    suspend fun openDeletion(
        expenseUuid: String,
        deletionRevision: Long,
        keyGeneration: Long,
        content: EncryptedFieldDto,
        shown: ExpenseWithBeneficiariesEntity,
    ): GroupExpenseOpening<GroupExpenseDeletionEntity> {
        fun unsealed(key: ByteArray) = content.openGroupExpenseDeletion(
            groupKey = key,
            groupUuid = group.uuid,
            expenseUuid = expenseUuid,
            deletionRevision = deletionRevision,
        )

        val key = keyFor(keyGeneration = keyGeneration, opens = { unsealed(it) != null }) { return it }
        val signed = unsealed(key) ?: return GroupExpenseOpening.Refused
        val authorKey = authorKeyFor(authorUuid = signed.authorUuid) { return it }

        if (!signed.isSignedBy(authorKey = authorKey, shown = shown)) return GroupExpenseOpening.Refused

        return GroupExpenseOpening.Opened(
            opened = GroupExpenseDeletionEntity(
                expenseUuid = expenseUuid,
                deletionRevision = deletionRevision,
                isDeleted = signed.deletion.isDeleted,
                authorUuid = signed.authorUuid,
            ),
        )
    }

    /**
     * The key of [keyGeneration] what is sealed under it opens under, [opens] telling, or else
     * what becomes of it, handed to [otherwise], which returns from the caller, see [openingOf].
     */
    private suspend inline fun keyFor(
        keyGeneration: Long,
        noinline opens: (key: ByteArray) -> Boolean,
        otherwise: (GroupExpenseOpening<Nothing>) -> Nothing,
    ): ByteArray {
        val found = keys.getOrPut(keyGeneration) { mutableListOf() }

        // A key another version of its generation opened under, or else the one the chain gives
        // for this one, see GroupKeyChain: what tells a key the chain leads to from any other is the
        // version asked for, and a member can seal one of their own under a key of theirs that the
        // chain leads to as well. Asked for once a generation, and again for every version opening
        // under none found until it comes to nothing new, so that such a version, or one that opens
        // under no key at all, has the server asked once more at most.
        found.firstOrNull(opens)?.let { return it }

        if (keyGeneration in settled) otherwise(openingOf(error = settled[keyGeneration], isKeyFound = found.isNotEmpty()))

        return when (val read = keyOf(group.uuid, keyGeneration, opens)) {
            is Result.Success -> read.data.also { key ->
                if (found.any { it contentEquals key }) settled[keyGeneration] = null else found += key
            }

            is Result.Error -> {
                settled[keyGeneration] = read.error

                otherwise(openingOf(error = read.error, isKeyFound = found.isNotEmpty()))
            }
        }
    }

    /**
     * The key [authorUuid]'s versions and flags are checked against, see [authorKeyOf], read once
     * for the pass, or [GroupExpenseOpening.Failed], handed to [otherwise], which returns from the
     * caller, when their pin could not be read.
     */
    private suspend inline fun authorKeyFor(
        authorUuid: String,
        otherwise: (GroupExpenseOpening<Nothing>) -> Nothing,
    ): ByteArray? {
        if (authorUuid in authorKeys) return authorKeys[authorUuid]

        return try {
            client.authorKeyOf(
                authorUuid = authorUuid,
                selfUuid = selfUuid,
                groupUuid = group.uuid,
                recorded = recorded,
            ).also { authorKeys[authorUuid] = it }
        } catch (_: MlsException) {
            failure = failure ?: Failure.Storage

            otherwise(GroupExpenseOpening.Failed)
        }
    }

    /**
     * What becomes of a version no key found for its generation opens, see [open]: when [error]
     * kept its key from being had, unreadable yet, or told later when the server could not be
     * asked, and refused otherwise, as a version is that does not open under its generation's key
     * when [isKeyFound], one was found, or that the chain gave this one, which it does not open.
     */
    private fun openingOf(error: GroupKeyChainError?, isKeyFound: Boolean): GroupExpenseOpening<Nothing> =
        when (error) {
            is GroupKeyChainError.Network -> {
                failure = failure ?: Failure.Network(error.error)

                GroupExpenseOpening.Failed
            }

            GroupKeyChainError.NotHeld,
            GroupKeyChainError.NotMember,
            GroupKeyChainError.Unreadable,
            -> if (isKeyFound) GroupExpenseOpening.Refused else GroupExpenseOpening.Unreadable

            null -> GroupExpenseOpening.Refused
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
            sharePercentage = beneficiary.sharePercentage,
            shareWeight = beneficiary.shareWeight,
        )
    },
    isSettlement = isSettlement,
)

/**
 * The one sealed field of a version of a group expense, as JSON: everything it says, who wrote it,
 * and their signature over all of it, in standard Base64, see [sealGroupExpense].
 */
@Serializable
private class SealedGroupExpense(
    /** A title sealed as [toSealedTitle] seals one. */
    val title: String,

    val description: String,

    /** An amount sealed as [toSealedAmount] seals one. */
    val amount: String,

    val timestamp: String,

    @SerialName("creditor_uuid")
    val creditorUuid: String,

    val beneficiaries: List<SealedGroupExpenseBeneficiary>,

    /** A flag sealed as [toSealedFlag] seals one. */
    @SerialName("is_settlement")
    val isSettlement: Int,

    @SerialName("author_uuid")
    val authorUuid: String,

    val signature: String,
)

/** One beneficiary of a [SealedGroupExpense], with their share. */
@Serializable
private class SealedGroupExpenseBeneficiary(
    @SerialName("user_uuid")
    val userUuid: String,

    /** An amount sealed as [toSealedAmount] seals one. */
    val share: String,

    /** A flag sealed as [toSealedFlag] seals one. */
    @SerialName("is_share_fixed")
    val isShareFixed: Int,

    /** A percentage sealed as [toSealedShareSetting] seals one. */
    @SerialName("share_percentage")
    val sharePercentage: String,

    /** A weight sealed as [toSealedShareSetting] seals one. */
    @SerialName("share_weight")
    val shareWeight: String,
)

/**
 * The one sealed field of a deletion flag of a group expense, as JSON: whether it is deleted, who
 * said so, and their signature over it, in standard Base64, see [sealGroupExpenseDeletion].
 */
@Serializable
private class SealedGroupExpenseDeletion(
    /** A flag sealed as [toSealedFlag] seals one. */
    @SerialName("is_deleted")
    val isDeleted: Int,

    @SerialName("author_uuid")
    val authorUuid: String,

    val signature: String,
)

/**
 * The most bytes a title takes in the JSON of a version: six for each of the characters
 * [MAX_TITLE_LENGTH] counts, which is what JSON writes a control character in.
 */
private const val SEALED_TITLE_JSON_SIZE = MAX_TITLE_LENGTH * 6

/** How many bytes this takes between its quotes in the JSON of a version. */
private fun String.sizeInJson(): Int = json.encodeToString(String.serializer(), this).encodeToByteArray().size - 2

/**
 * A title as a version seals it: padded with spaces until it takes [SEALED_TITLE_JSON_SIZE] bytes
 * in the JSON around it, whatever it says, since a letter takes one there, an accented one or a
 * quote two, and a control character six. AES-GCM does not hide how long what it seals is, and the
 * server would read something of a title from the length of the version, as a one-time expense's
 * would from its own field, see `expense.OneTimeExpenseSealing`. A title is saved without the
 * whitespace around it, see `database.domain.trimmed`, so that opening it takes off nothing but the
 * padding, and it is signed without it.
 */
private fun String.toSealedTitle(): String = this + " ".repeat((SEALED_TITLE_JSON_SIZE - sizeInJson()).coerceAtLeast(0))

/** Whether this takes as many bytes as [toSealedTitle] makes a title take. */
private fun String.isSealedTitle(): Boolean = sizeInJson() == SEALED_TITLE_JSON_SIZE

/**
 * A flag as a version, or a deletion flag, seals one: `1` or `0`, and not `true` or `false`, which
 * differ in length, as a one-time expense seals its own, see `expense.OneTimeExpenseSealing`.
 * AES-GCM does not hide how long what it seals is, and a deletion flag would otherwise tell the
 * server by its length alone whether it deletes the expense or restores it.
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
