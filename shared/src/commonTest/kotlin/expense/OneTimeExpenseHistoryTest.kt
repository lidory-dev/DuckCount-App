package expense

import database.data.remote.BeneficiaryDto
import database.data.remote.EncryptedFieldDto
import database.data.remote.OneTimeExpenseHistoryEntryDto
import database.domain.Beneficiary
import database.domain.CurrencyCatalog
import database.domain.ExpenseWithBeneficiaries
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private const val EXPENSE = "8d1e5823-c00c-83b7-8212-500089e3b975"
private const val CREDITOR = "01234567-89ab-4cde-8f01-23456789abcd"
private const val BOB = "11111111-1111-1111-1111-111111111111"

/** When the expense was created, as its own date says. */
private const val CREATED = "2026-10-12T09:00:00"

@OptIn(ExperimentalTime::class)
class OneTimeExpenseHistoryTest {

    @Test
    fun `an expense never edited is the one version it was created as`() {
        val history = oneTimeExpenseHistory(shown = version(0), replaced = emptyList(), timeZone = TimeZone.UTC)

        assertEquals(1, history.size)
        assertTrue(history.single().isCreation)
        assertEquals(LocalDateTime(2026, 10, 12, 9, 0), history.single().madeAt)
    }

    @Test
    fun `each version was made when the edit that replaced the one before it was`() {
        val history = oneTimeExpenseHistory(
            shown = version(2),
            replaced = listOf(replaced(1, "2026-10-13T10:30:00.000Z"), replaced(0, "2026-10-13T08:15:42.317Z")),
            timeZone = TimeZone.UTC,
        )

        assertEquals(listOf("v2", "v1", "v0"), history.map { it.expense.title })
        assertEquals(listOf(false, false, true), history.map { it.isCreation })
        assertEquals(
            listOf(
                LocalDateTime(2026, 10, 13, 10, 30),
                LocalDateTime(2026, 10, 13, 8, 15, 42, 317_000_000),
                LocalDateTime(2026, 10, 12, 9, 0),
            ),
            history.map { it.madeAt },
        )
    }

    @Test
    fun `when an edit was made is told in the device's time zone`() {
        val history = oneTimeExpenseHistory(
            shown = version(1),
            replaced = listOf(replaced(0, "2026-10-13T22:30:00.000Z")),
            timeZone = UtcOffset(hours = 2).asTimeZone(),
        )

        assertEquals(LocalDateTime(2026, 10, 14, 0, 30), history.first().madeAt)
    }

    /** It was written with no time zone, as its creditor's clock read it. */
    @Test
    fun `when the expense was created is its own date as written in any time zone`() {
        val history = oneTimeExpenseHistory(
            shown = version(1),
            replaced = listOf(replaced(0, "2026-10-13T22:30:00.000Z")),
            timeZone = UtcOffset(hours = -4).asTimeZone(),
        )

        assertEquals(LocalDateTime(2026, 10, 12, 9, 0), history.last().madeAt)
    }

    /** An instant parses further either way than a date can be written for. */
    @Test
    fun `an edit the server says was made past any date is made at no known time`() {
        listOf("+1000000000-06-01T00:00:00Z", "-1000000000-06-01T00:00:00Z").forEach { at ->
            val history = oneTimeExpenseHistory(
                shown = version(1),
                replaced = listOf(replaced(0, at)),
                timeZone = UtcOffset(hours = 2).asTimeZone(),
            )

            assertNull(history.first().madeAt, at)
        }
    }

    @Test
    fun `versions go by the revision they were signed at and not by the server's order`() {
        val history = oneTimeExpenseHistory(
            shown = version(3),
            replaced = listOf(
                replaced(0, "2026-10-13T08:00:00.000Z"),
                replaced(2, "2026-10-13T10:00:00.000Z"),
                replaced(1, "2026-10-13T09:00:00.000Z"),
            ),
            timeZone = TimeZone.UTC,
        )

        assertEquals(listOf("v3", "v2", "v1", "v0"), history.map { it.expense.title })
        assertEquals(listOf(3L, 2L, 1L, 0L), history.map { it.revision })
        assertEquals(LocalDateTime(2026, 10, 13, 10, 0), history[0].madeAt)
        assertEquals(LocalDateTime(2026, 10, 13, 9, 0), history[1].madeAt)
        assertEquals(LocalDateTime(2026, 10, 13, 8, 0), history[2].madeAt)
    }

    /**
     * The server answers with what it holds now. A device that has not caught up with an edit,
     * or a server serving the version shown again as an earlier one, puts versions in the
     * history that do not come before the one shown.
     */
    @Test
    fun `nothing at or after the version shown is shown as coming before it`() {
        val history = oneTimeExpenseHistory(
            shown = version(1),
            replaced = listOf(
                replaced(2, "2026-10-13T11:00:00.000Z"),
                replaced(1, "2026-10-13T10:00:00.000Z"),
                replaced(0, "2026-10-13T09:00:00.000Z"),
            ),
            timeZone = TimeZone.UTC,
        )

        assertEquals(listOf("v1", "v0"), history.map { it.expense.title })
        assertEquals(LocalDateTime(2026, 10, 13, 9, 0), history.first().madeAt)
    }

    /** Two edits racing each other can both record the version they replace. */
    @Test
    fun `a revision recorded twice is shown once as the server first lists it`() {
        val history = oneTimeExpenseHistory(
            shown = version(2),
            replaced = listOf(
                replaced(0, "2026-10-13T10:00:00.000Z"),
                replaced(0, "2026-10-13T09:00:00.000Z"),
            ),
            timeZone = TimeZone.UTC,
        )

        assertEquals(listOf("v2", "v0"), history.map { it.expense.title })
        assertEquals(LocalDateTime(2026, 10, 13, 10, 0), history.first().madeAt)
    }

    /** An edit that got no answer and never landed leaves its revision unused. */
    @Test
    fun `a revision skipped by an edit that never landed changes nothing`() {
        val history = oneTimeExpenseHistory(
            shown = version(2),
            replaced = listOf(replaced(0, "2026-10-13T10:00:00.000Z")),
            timeZone = TimeZone.UTC,
        )

        assertEquals(listOf(false, true), history.map { it.isCreation })
        assertEquals(LocalDateTime(2026, 10, 13, 10, 0), history.first().madeAt)
    }

    /** Only its signed revision says a version is the expense as created, not where it ends up. */
    @Test
    fun `a version whose predecessor the server did not return is no creation and made at no known time`() {
        val history = oneTimeExpenseHistory(
            shown = version(2),
            replaced = listOf(replaced(1, "2026-10-13T10:00:00.000Z")),
            timeZone = TimeZone.UTC,
        )

        assertEquals(LocalDateTime(2026, 10, 13, 10, 0), history.first().madeAt)
        assertFalse(history.last().isCreation)
        assertNull(history.last().madeAt)
    }

    @Test
    fun `an edited expense whose history the server withholds is not shown as created`() {
        val history = oneTimeExpenseHistory(shown = version(4), replaced = emptyList(), timeZone = TimeZone.UTC)

        assertFalse(history.single().isCreation)
        assertNull(history.single().madeAt)
    }

    @Test
    fun `when the server says an edit was made is only taken when it is a time`() {
        assertEquals(Instant.parse("2026-10-13T08:15:42.317Z"), entry(modifiedAt = "2026-10-13T08:15:42.317Z").replacedAt())

        listOf("2026-10-13T08:15:42.317", "yesterday", "").forEach { modifiedAt ->
            assertNull(entry(modifiedAt = modifiedAt).replacedAt(), modifiedAt)
        }
    }

    /**
     * The library throws on a string that is not valid UTF-16, so no entry naming one reaches it.
     * Refused before anything is opened, so no key is needed to tell.
     */
    @Test
    fun `an entry naming a beneficiary by anything but a uuid is not opened`() {
        val record = OneTimeExpenseKeyRecord(
            expenseUuid = EXPENSE,
            key = ByteArray(32),
            creditorUuid = CREDITOR,
            nonce = ByteArray(16),
            creditorSignatureKey = ByteArray(32),
            beneficiaryUuids = listOf(BOB),
        )

        val unopened = entry(beneficiaryUuid = "11111111-1111-1111-1111-11111111111\uD800")
            .unsealOneTime(expenseUuid = EXPENSE, record = record)

        assertNull(unopened)
    }

    private fun version(revision: Long) = OpenedOneTimeExpense(
        expense = ExpenseWithBeneficiaries(
            uuid = EXPENSE,
            title = "v$revision",
            timestamp = CREATED,
            description = "",
            amount = "20",
            creditorUuid = CREDITOR,
            currency = CurrencyCatalog.defaultCurrency,
            beneficiaries = listOf(
                Beneficiary(expenseUuid = EXPENSE, userUuid = BOB, share = "20", isShareFixed = false),
            ),
        ),
        revision = revision,
    )

    private fun replaced(revision: Long, at: String) =
        ReplacedVersion(version = version(revision), replacedAt = Instant.parse(at))

    private fun entry(
        modifiedAt: String = "2026-10-13T08:15:42.317Z",
        beneficiaryUuid: String = BOB,
    ): OneTimeExpenseHistoryEntryDto {
        val sealed = EncryptedFieldDto(ciphertext = "Y2lwaGVy", nonce = "bm9uY2U=")

        return OneTimeExpenseHistoryEntryDto(
            modified_at = modifiedAt,
            previous_signature = sealed,
            previous_title = sealed,
            previous_description = sealed,
            previous_amount = sealed,
            previous_currency = sealed,
            previous_timestamp = sealed,
            previous_beneficiaries = listOf(
                BeneficiaryDto(
                    user_uuid = beneficiaryUuid,
                    share = sealed,
                    is_share_fixed = sealed,
                    share_percentage = sealed,
                ),
            ),
        )
    }
}
