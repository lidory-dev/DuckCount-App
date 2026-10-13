package expense

import database.data.remote.EncryptedFieldDto
import database.data.remote.OneTimeExpenseHistoryEntryDto
import database.data.remote.OneTimeExpenseRequestBody
import database.domain.Beneficiary
import database.domain.CurrencyCatalog
import database.domain.ExpenseWithBeneficiaries
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.MlsNamespace
import uniffi.lib_duckcount.MlsStore
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

    /** The history keeps no settlement flag, and the signature covers it. */
    @Test
    fun `a version is checked with the settlement flag it is given`() {
        val entry = expense().signedBy(creditor, revision = 1)

        assertNotNull(entry.open(isSettled = false))
        assertNull(entry.open(isSettled = true))
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

    private fun expense(title: String = "Pizzas") = ExpenseWithBeneficiaries(
        uuid = EXPENSE,
        title = title,
        timestamp = "2026-10-12T09:00:00",
        description = "",
        amount = "20",
        creditorUuid = CREDITOR,
        isSettlement = false,
        currency = CurrencyCatalog.defaultCurrency,
        beneficiaries = listOf(
            Beneficiary(expenseUuid = EXPENSE, userUuid = BOB, share = "12", isShareFixed = false),
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
        isSettled: Boolean = false,
        record: OneTimeExpenseKeyRecord = this@OneTimeExpenseHistoryVerificationTest.record,
    ) = unsealOneTime(expenseUuid = EXPENSE, isSettled = isSettled, record = record)
}

/** Holds an MLS client's state for the length of a test. */
private class InMemoryMlsStore : MlsStore {
    private val values = HashMap<Pair<MlsNamespace, String>, ByteArray>()

    override fun get(namespace_: MlsNamespace, key: ByteArray): ByteArray? = values[namespace_ to key.encoded()]

    override fun put(namespace_: MlsNamespace, key: ByteArray, value: ByteArray) {
        values[namespace_ to key.encoded()] = value
    }

    override fun delete(namespace_: MlsNamespace, key: ByteArray) {
        values.remove(namespace_ to key.encoded())
    }

    override fun keys(namespace_: MlsNamespace): List<ByteArray> =
        values.keys.filter { it.first == namespace_ }.map { Base64.Default.decode(it.second) }

    private fun ByteArray.encoded() = Base64.Default.encode(this)
}
