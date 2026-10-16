package expense

import database.data.remote.EncryptedFieldDto
import database.data.remote.ExpenseDto
import database.data.remote.OneTimeExpenseHistoryEntryDto
import database.data.remote.OneTimeExpenseRequestBody
import database.domain.Beneficiary
import database.domain.CurrencyCatalog
import database.domain.ExpenseWithBeneficiaries
import mls.InMemoryMlsStore
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val EXPENSE = "8d1e5823-c00c-83b7-8212-500089e3b975"
private const val CREDITOR = "01234567-89ab-4cde-8f01-23456789abcd"
private const val BOB = "11111111-1111-1111-1111-111111111111"
private const val CAROL = "22222222-2222-2222-2222-222222222222"

/** When the creditor marked the expense, in milliseconds since the epoch. */
private const val MARKED_AT = 1_760_400_000_000

/**
 * A version from the history opens only as its creditor signed it, through the library itself:
 * the server holds every entry, and every participant holds the key they are sealed under.
 */
class OneTimeExpenseHistoryVerificationTest {

    private val creditor = MlsClient(userUuid = CREDITOR, secret = ByteArray(32) { 1 }, store = InMemoryMlsStore())

    /** A beneficiary, who holds the expense's key as every participant does. */
    private val bob = MlsClient(userUuid = BOB, secret = ByteArray(32) { 2 }, store = InMemoryMlsStore())

    private val expenseKey = generateSymmetricEncryptionKey()

    private val record = OneTimeExpenseKeyRecord(
        expenseUuid = EXPENSE,
        key = expenseKey,
        creditorUuid = CREDITOR,
        nonce = ByteArray(16),
        creditorSignatureKey = creditor.signaturePublicKey(),
        beneficiaryUuids = listOf(BOB, CAROL),
    )

    @Test
    fun `a version its creditor signed opens at the revision they signed it at`() {
        val opened = assertNotNull(expense().signedBy(creditor, revision = 3).open())

        assertEquals(3, opened.revision)
        assertEquals("Pizzas", opened.expense.title)
        assertEquals(CREDITOR, opened.expense.creditorUuid)
        assertEquals(mapOf(BOB to "12", CAROL to "8"), opened.expense.beneficiaries.associate { it.userUuid to it.share })
    }

    @Test
    fun `a version a beneficiary signed under the expense's own key does not open`() {
        assertNull(expense(title = "Forged").signedBy(bob, revision = 4).open())
    }

    @Test
    fun `shares moved from one beneficiary to another do not open`() {
        val entry = expense().signedBy(creditor, revision = 1)
        val (first, second) = entry.previous_beneficiaries

        val swapped = entry.copy(
            previous_beneficiaries = listOf(first.copy(share = second.share), second.copy(share = first.share)),
        )

        assertNull(swapped.open())
    }

    @Test
    fun `a field taken from another version does not open`() {
        val older = expense(title = "Before").signedBy(creditor, revision = 1)
        val newer = expense(title = "After").signedBy(creditor, revision = 2)

        assertNull(newer.copy(previous_title = older.previous_title).open())
    }

    @Test
    fun `a version of another expense does not open`() {
        assertNull(expense().signedBy(creditor, revision = 1, key = generateSymmetricEncryptionKey()).open())
    }

    @Test
    fun `a mark its creditor signed opens with what it says`() {
        listOf(false, true).forEach { isSettled ->
            val mark = assertNotNull(listed(settledMark = mark(markedAt = MARKED_AT, isSettled = isSettled)).openSettledMark(record))

            assertEquals(EXPENSE, mark.expenseUuid)
            assertEquals(MARKED_AT, mark.markedAt)
            assertEquals(isSettled, mark.isSettled)
        }
    }

    @Test
    fun `a share set by hand and an even one open as they were sealed`() {
        val opened = assertNotNull(expense(isBobShareFixed = true).signedBy(creditor, revision = 1).open())

        assertEquals(
            mapOf(BOB to true, CAROL to false),
            opened.expense.beneficiaries.associate { it.userUuid to it.isShareFixed },
        )
    }

    /** Sealing hides whether a share was set by hand but not how long that is, which would say it as well. */
    @Test
    fun `a share set by hand and an even one seal to the same length`() {
        val sealed = expense(isBobShareFixed = true).signedBy(creditor, revision = 1).previous_beneficiaries
            .associate { it.user_uuid to Base64.Default.decode(it.is_share_fixed.ciphertext).size }

        assertEquals(sealed.getValue(BOB), sealed.getValue(CAROL))
    }

    /** Sealing hides what a mark says but not how long it is, which would say it as well. */
    @Test
    fun `a settled mark and one that is not seal to the same length`() {
        val (settled, notSettled) = listOf(true, false).map { isSettled ->
            Base64.Default.decode(mark(markedAt = MARKED_AT, isSettled = isSettled).ciphertext).size
        }

        assertEquals(settled, notSettled)
    }

    /** A version says nothing of whether the expense was paid back. */
    @Test
    fun `an expense nobody marked has no mark`() {
        assertNull(listed(settledMark = null).openSettledMark(record))
    }

    @Test
    fun `a mark a beneficiary signed under the expense's own key does not open`() {
        assertNull(listed(settledMark = mark(markedAt = MARKED_AT, isSettled = true, client = bob)).openSettledMark(record))
    }

    @Test
    fun `a mark made for another expense does not open on this one`() {
        // Under this expense's key, so only the uuid it was signed for differs.
        val otherExpense = OneTimeExpenseKeyRecord(
            expenseUuid = "9e2f6934-d11d-84c8-9323-611190f4ca86",
            key = expenseKey,
            creditorUuid = CREDITOR,
            nonce = ByteArray(16),
            creditorSignatureKey = creditor.signaturePublicKey(),
            beneficiaryUuids = listOf(BOB, CAROL),
        )

        val moved = sealSettledMark(record = otherExpense, markedAt = MARKED_AT, isSettled = true, client = creditor)

        assertNull(listed(settledMark = moved).openSettledMark(record))
    }

    @Test
    fun `a mark sealed under another key does not open`() {
        val otherKey = OneTimeExpenseKeyRecord(
            expenseUuid = EXPENSE,
            key = generateSymmetricEncryptionKey(),
            creditorUuid = CREDITOR,
            nonce = ByteArray(16),
            creditorSignatureKey = creditor.signaturePublicKey(),
            beneficiaryUuids = listOf(BOB, CAROL),
        )

        val sealed = sealSettledMark(record = otherKey, markedAt = MARKED_AT, isSettled = true, client = creditor)

        assertNull(listed(settledMark = sealed).openSettledMark(record))
    }

    /** A mark is no part of a version, which opens the same with one or without, see [openSettledMark]. */
    @Test
    fun `marking an expense leaves its version opening as it was`() {
        val opened = assertNotNull(listed(settledMark = mark(markedAt = MARKED_AT, isSettled = true)).unsealOneTime(record))

        assertEquals(1, opened.revision)
    }

    private fun mark(markedAt: Long, isSettled: Boolean, client: MlsClient = creditor): EncryptedFieldDto =
        assertNotNull(sealSettledMark(record = record, markedAt = markedAt, isSettled = isSettled, client = client))

    /** The expense as the server lists it, at revision 1, carrying [settledMark]. */
    private fun listed(settledMark: EncryptedFieldDto?): ExpenseDto {
        val body = assertNotNull(
            expense().sealOneTime(
                expenseKey = expenseKey,
                revision = 1,
                client = creditor,
                keyCopy = EncryptedFieldDto(ciphertext = "Y29weQ==", nonce = "bm9uY2U="),
            ),
        )

        return ExpenseDto(
            uuid = EXPENSE,
            creditor_uuid = CREDITOR,
            title = body.title,
            description = body.description,
            amount = body.amount,
            currency = body.currency,
            timestamp = body.timestamp,
            beneficiaries = body.beneficiaries,
            signature = body.signature,
            key_copy = null,
            is_settled = settledMark,
        )
    }

    @Test
    fun `a version does not open for a record holding its creditor to another key`() {
        val pinnedOtherwise = OneTimeExpenseKeyRecord(
            expenseUuid = EXPENSE,
            key = expenseKey,
            creditorUuid = CREDITOR,
            nonce = ByteArray(16),
            creditorSignatureKey = bob.signaturePublicKey(),
            beneficiaryUuids = listOf(BOB, CAROL),
        )

        assertNull(expense().signedBy(creditor, revision = 1).open(record = pinnedOtherwise))
    }

    private fun expense(title: String = "Pizzas", isBobShareFixed: Boolean = false) = ExpenseWithBeneficiaries(
        uuid = EXPENSE,
        title = title,
        timestamp = "2026-10-12T09:00:00",
        description = "",
        amount = "20",
        creditorUuid = CREDITOR,
        currency = CurrencyCatalog.defaultCurrency,
        beneficiaries = listOf(
            Beneficiary(expenseUuid = EXPENSE, userUuid = BOB, share = "12", isShareFixed = isBobShareFixed),
            Beneficiary(expenseUuid = EXPENSE, userUuid = CAROL, share = "8", isShareFixed = false),
        ),
    )

    /** The version as the history would hold it once an edit replaced it. */
    private fun ExpenseWithBeneficiaries.signedBy(
        client: MlsClient,
        revision: Long,
        key: ByteArray = expenseKey,
    ): OneTimeExpenseHistoryEntryDto =
        assertNotNull(
            sealOneTime(
                expenseKey = key,
                revision = revision,
                client = client,
                keyCopy = EncryptedFieldDto(ciphertext = "Y29weQ==", nonce = "bm9uY2U="),
            ),
        ).toHistoryEntry()

    private fun OneTimeExpenseRequestBody.toHistoryEntry() = OneTimeExpenseHistoryEntryDto(
        modified_at = "2026-10-13T08:15:42.317Z",
        previous_signature = signature,
        previous_title = title,
        previous_description = description,
        previous_amount = amount,
        previous_currency = currency,
        previous_timestamp = timestamp,
        previous_beneficiaries = beneficiaries,
    )

    private fun OneTimeExpenseHistoryEntryDto.open(
        record: OneTimeExpenseKeyRecord = this@OneTimeExpenseHistoryVerificationTest.record,
    ) = unsealOneTime(expenseUuid = EXPENSE, record = record)
}
