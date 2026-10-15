package expense

import database.data.remote.ExpenseDto
import database.data.remote.OneTimeExpenseHistoryEntryDto
import database.domain.ExpenseWithBeneficiaries
import kotlinx.datetime.DateTimeArithmeticException
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * One version of a one-time expense, as its history tells it: what it said, whether it is the
 * expense as it was created, and when it was made, see [oneTimeExpenseHistory].
 *
 * Whoever made it is its creditor, [ExpenseWithBeneficiaries.creditorUuid]: nobody else can sign
 * a version, and only a signed one is ever shown.
 */
class OneTimeExpenseVersion(
    val expense: ExpenseWithBeneficiaries,

    /** The revision its creditor signed it at, which no other version in its history has. */
    val revision: Long,

    val isCreation: Boolean,

    /**
     * For the version created, the expense's own date, as its creditor's clock read it and as
     * the app shows that date everywhere: it was written with no time zone. For any other, when
     * the server says the edit was made, in the device's time zone. Null when not known.
     */
    val madeAt: LocalDateTime?,
)

/** A version from before the one shown, opened, and when the edit that replaced it was made. */
@OptIn(ExperimentalTime::class)
internal class ReplacedVersion(
    val version: OpenedOneTimeExpense,

    /** The server's word, or null when what it said is not a time. */
    val replacedAt: Instant?,
)

/**
 * Opens a version of the one-time expense [expenseUuid] as its history holds it, or returns
 * null, exactly as [ExpenseDto.unsealOneTime] does for one listed: only a version that opens
 * whole under [record]'s key, signed by its creditor with the key [record] holds them to. So
 * the server can leave versions out, but neither it nor a participant, who holds the key too,
 * can make one up.
 *
 * The creditor is [record]'s, as the history names none: nobody else can have made a version.
 * Whether the expense was paid back is no part of a version, nor of its history, see
 * [openSettledMark].
 */
internal fun OneTimeExpenseHistoryEntryDto.unsealOneTime(
    expenseUuid: String,
    record: OneTimeExpenseKeyRecord,
): OpenedOneTimeExpense? {
    // Before anything reaches the library, which throws on a string that is not valid UTF-16
    // rather than refusing it, as the listing is, see [OneTimeExpenseSync.refresh].
    if (!previous_beneficiaries.all { it.user_uuid.isCanonicalUuid() }) return null

    return ExpenseDto(
        uuid = expenseUuid,
        creditor_uuid = record.creditorUuid,
        title = previous_title,
        description = previous_description,
        amount = previous_amount,
        currency = previous_currency,
        timestamp = previous_timestamp,
        beneficiaries = previous_beneficiaries,
        signature = previous_signature,
        key_copy = null,
        is_settled = null,
    ).unsealOneTime(record = record)
}

/** When the server says the edit that replaced this entry's version was made, or null. */
@OptIn(ExperimentalTime::class)
internal fun OneTimeExpenseHistoryEntryDto.replacedAt(): Instant? = Instant.parseOrNull(modified_at)

/**
 * The history of a one-time expense: [shown], the version this device shows, then each one
 * [replaced] holds from before it, newest first.
 *
 * In the order of the revisions they were signed at, and not in the server's, which nothing
 * vouches for. Only versions from before [shown] are kept: the server answers with what it holds
 * now, which can be ahead of what this device has caught up with, and nothing is shown as coming
 * before a version it does not. Each revision is kept once, the first the server lists: the
 * server records the version an edit replaces before it locks the expense, so two edits racing
 * each other can both record the same one.
 *
 * The version the expense was created as is told by its revision, which is signed, and was made
 * when its own date says: an edit keeps the date. Any other version was made when the edit that
 * replaced the one before it was, which only the server says, and which is not known when the
 * server did not return that version. The two are not told on the same clock: the expense's
 * date carries no time zone, so it is taken as written, while an edit's is an instant, told in
 * [timeZone]. They disagree for somebody whose time zone is not the creditor's.
 */
@OptIn(ExperimentalTime::class)
internal fun oneTimeExpenseHistory(
    shown: OpenedOneTimeExpense,
    replaced: List<ReplacedVersion>,
    timeZone: TimeZone,
): List<OneTimeExpenseVersion> {
    val earlier = replaced
        .filter { it.version.revision < shown.revision }
        .sortedByDescending { it.version.revision }
        .distinctBy { it.version.revision }

    val versions = listOf(shown) + earlier.map { it.version }

    return versions.mapIndexed { index, version ->
        val isCreation = version.revision == FIRST_REVISION

        OneTimeExpenseVersion(
            expense = version.expense,
            revision = version.revision,
            isCreation = isCreation,
            madeAt = if (isCreation) {
                LocalDateTime.Formats.ISO.parseOrNull(version.expense.timestamp)
            } else {
                // The version just before this one, which the edit that made this one replaced.
                earlier.getOrNull(index)?.replacedAt?.toLocalDateTimeOrNull(timeZone)
            },
        )
    }
}

/**
 * This instant in [timeZone], or null when no date can be written for it there: an instant
 * parses up to a billion years either way, further than a date goes, and the server's word is
 * all that says when an edit was made.
 */
@OptIn(ExperimentalTime::class)
private fun Instant.toLocalDateTimeOrNull(timeZone: TimeZone): LocalDateTime? =
    try {
        toLocalDateTime(timeZone)
    } catch (_: DateTimeArithmeticException) {
        null
    }
