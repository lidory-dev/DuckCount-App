package expense

import app.lidory.duckcount.ExpenseLimits
import database.data.remote.EncryptedFieldDto
import database.data.remote.ExpenseDto
import database.data.remote.OneTimeExpenseHistoryEntryDto
import database.data.remote.OneTimeExpenseRequestBody
import database.domain.Beneficiary
import database.domain.Currency
import database.domain.CurrencyCatalog
import database.domain.ExpenseWithBeneficiaries
import database.domain.withCanonicalAmounts
import mls.InMemoryMlsStore
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.OneTimeExpenseBeneficiary
import uniffi.lib_duckcount.OneTimeExpenseManifest
import uniffi.lib_duckcount.SealedValue
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import vault.sealBound
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
        assertEquals(mapOf(BOB to "12.00", CAROL to "8.00"), opened.expense.beneficiaries.associate { it.userUuid to it.share })
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

    @Test
    fun `a share set as a percentage opens with it and the others without`() {
        val opened = assertNotNull(
            expense(isBobShareFixed = true, bobSharePercentage = "60").signedBy(creditor, revision = 1).open(),
        )

        assertEquals(
            mapOf(BOB to "60", CAROL to null),
            opened.expense.beneficiaries.associate { it.userUuid to it.sharePercentage },
        )
    }

    /** Sealing hides who pays a percentage but not how long what it seals is, which would say it as well. */
    @Test
    fun `a share set as a percentage of any length and one that is none seal to the same length`() {
        val lengths = listOf(null, "5", "60", "33.33", "100.00").flatMap { percentage ->
            expense(bobSharePercentage = percentage).signedBy(creditor, revision = 1).previous_beneficiaries
                .map { Base64.Default.decode(it.share_percentage.ciphertext).size }
        }

        assertEquals(1, lengths.toSet().size)
    }

    /** It would show one thing and charge another, and an edit would work the share out again from it. */
    @Test
    fun `a share its percentage does not come to does not open`() {
        assertNull(expense(bobSharePercentage = "10").signedBy(creditor, revision = 1).open())
    }

    @Test
    fun `a percentage moved from one beneficiary to another does not open`() {
        val entry = expense(bobSharePercentage = "60").signedBy(creditor, revision = 1)
        val (first, second) = entry.previous_beneficiaries

        val moved = entry.copy(
            previous_beneficiaries = listOf(
                first.copy(share_percentage = second.share_percentage),
                second.copy(share_percentage = first.share_percentage),
            ),
        )

        assertNull(moved.open())
    }

    /** Sealing hides how large an amount is but not how long it is written, which would say it as well. */
    @Test
    fun `an amount and a share seal to the same length however large they are`() {
        val amounts = listOf(
            Triple("3.00", "2.00", "1.00"),
            Triple("20.00", "12.00", "8.00"),
            Triple("300000.00", "200000.00", "100000.00"),
            Triple("999999999999999.99", "666666666666666.66", "333333333333333.33"),
        )

        val lengths = amounts.flatMap { (amount, bob, carol) ->
            val expense = expense().let { expense ->
                expense.copy(
                    amount = amount,
                    beneficiaries = expense.beneficiaries.map {
                        it.copy(share = if (it.userUuid == BOB) bob else carol)
                    },
                )
            }

            val entry = expense.signedBy(creditor, revision = 1)

            assertEquals(amount, assertNotNull(entry.open(), amount).expense.amount)

            listOf(entry.previous_amount) + entry.previous_beneficiaries.map { it.share }
        }.map { Base64.Default.decode(it.ciphertext).size }

        assertEquals(1, lengths.toSet().size)
    }

    /** In a currency without subunits too: its length would otherwise say it has none. */
    @Test
    fun `an amount seals to the same length in every currency`() {
        val lengths = CurrencyCatalog.defaultCurrencies.map { currency ->
            Base64.Default.decode(expense(currency = currency).signedBy(creditor, revision = 1).previous_amount.ciphertext).size
        }

        assertEquals(1, lengths.toSet().size)
    }

    /**
     * Signed by its creditor over just what it holds, so that nothing but the length refuses it, as a
     * client of its own would seal it: unsealed under the expense's key, each field bound to its place.
     */
    @Test
    fun `an expense whose amounts are not padded to the one length does not open`() {
        val entry = expense().signedBy(creditor, revision = 1)

        // Padded as it should be, as a control: the same fields, signed again unchanged, open.
        assertNotNull(entry.signedAgain().open())

        val anotherLength = mapOf(
            "an amount not padded" to entry.copy(previous_amount = sealedAs("one_time_expense:amount", "20.00")),
            "an amount padded short" to entry.copy(previous_amount = sealedAs("one_time_expense:amount", "20.00".padEnd(17))),
            "an amount padded long" to entry.copy(previous_amount = sealedAs("one_time_expense:amount", "20.00".padEnd(19))),
            "a share not padded" to entry.copy(
                previous_beneficiaries = entry.previous_beneficiaries.map { beneficiary ->
                    if (beneficiary.user_uuid == BOB) {
                        beneficiary.copy(share = sealedAs("one_time_expense:share:$BOB", "12.00"))
                    } else {
                        beneficiary
                    }
                },
            ),
        )

        anotherLength.forEach { (change, changed) -> assertNull(changed.signedAgain().open(), change) }
    }

    /** Sealing hides what a title says but not how long it is, which would say something of it as well. */
    @Test
    fun `a title seals to the same length whatever it says`() {
        val most = ExpenseLimits.MAX_TITLE_LENGTH

        val titles = listOf(
            "",
            "a",
            "Pizzas",
            "a".repeat(most),
            // Two bytes each in UTF-8, three, and four for two characters.
            "é".repeat(most),
            "€".repeat(most),
            "😀".repeat(most / 2),
        )

        val lengths = titles.map { title ->
            val entry = expense(title = title).signedBy(creditor, revision = 1)

            assertEquals(title, assertNotNull(entry.open(), title).expense.title)

            Base64.Default.decode(entry.previous_title.ciphertext).size
        }

        assertEquals(1, lengths.toSet().size)
    }

    /** Signed by its creditor over just what it holds, so that nothing but the length refuses it. */
    @Test
    fun `an expense whose title is not padded to the one length does not open`() {
        val entry = expense().signedBy(creditor, revision = 1)
        val size = ExpenseLimits.MAX_TITLE_LENGTH * 3

        // Padded as it should be, as a control: the same fields, signed again unchanged, open.
        assertEquals("Pizzas", assertNotNull(entry.signedAgain().open()).expense.title)
        assertEquals(
            "Pizzas",
            assertNotNull(
                entry.copy(previous_title = sealedAs("one_time_expense:title", "Pizzas".padEnd(size))).signedAgain().open(),
            ).expense.title,
        )

        listOf("Pizzas", "Pizzas".padEnd(size - 1), "Pizzas".padEnd(size + 1)).forEach { title ->
            val changed = entry.copy(previous_title = sealedAs("one_time_expense:title", title))

            assertNull(changed.signedAgain().open(), "${title.length} characters")
        }
    }

    /**
     * Sealing hides what a description says but not how long it is: all that shows is which of the
     * three sizes it fits, counted in the bytes it takes in UTF-8.
     */
    @Test
    fun `a description seals to the length of the size it fits`() {
        val most = ExpenseLimits.MAX_DESCRIPTION_LENGTH

        val sizes = listOf(
            // Nothing and nearly nothing, up to the first size exactly, an é taking two.
            listOf("", "a", "a".repeat(128), "é".repeat(64)),
            // Just past it, up to the second exactly, a € taking three.
            listOf("a".repeat(129), "é".repeat(65), "a".repeat(500), "€".repeat(170)),
            // Just past it, up to the third exactly.
            listOf("€".repeat(171), "€".repeat(most)),
        ).map { descriptions ->
            descriptions.map { description ->
                val entry = expense(description = description).signedBy(creditor, revision = 1)

                assertEquals(description, assertNotNull(entry.open(), description).expense.description)

                Base64.Default.decode(entry.previous_description.ciphertext).size
            }.toSet().single()
        }

        assertEquals(listOf(144, 528, 1516), sizes)
    }

    /** Signed by its creditor over just what it holds, so that nothing but the size refuses it. */
    @Test
    fun `an expense whose description is of none of the sizes does not open`() {
        val entry = expense().signedBy(creditor, revision = 1)

        fun withDescription(description: String) =
            entry.copy(previous_description = sealedAs("one_time_expense:description", description)).signedAgain().open()

        // Each of the sizes, as a control.
        listOf(128, 512, ExpenseLimits.MAX_DESCRIPTION_LENGTH * 3).forEach { size ->
            assertEquals("", assertNotNull(withDescription("".padEnd(size)), "$size bytes").expense.description)
        }

        listOf(0, 127, 129, 256, 511, 513, ExpenseLimits.MAX_DESCRIPTION_LENGTH * 3 + 1).forEach { size ->
            assertNull(withDescription("".padEnd(size)), "$size bytes")
        }
    }

    /** [text] sealed under the expense's key, bound to [context], as a client of its own would. */
    private fun sealedAs(context: String, text: String) =
        assertNotNull(sealBound(key = expenseKey, context = context, plaintext = text.encodeToByteArray()))

    /** This entry with its creditor's signature made anew over what it holds now. */
    private fun OneTimeExpenseHistoryEntryDto.signedAgain(): OneTimeExpenseHistoryEntryDto {
        fun EncryptedFieldDto.toSealedValue() =
            SealedValue(nonce = Base64.Default.decode(nonce), ciphertext = Base64.Default.decode(ciphertext))

        val signature = creditor.signOneTimeExpense(
            manifest = OneTimeExpenseManifest(
                revision = 1u,
                title = previous_title.toSealedValue(),
                description = previous_description.toSealedValue(),
                amount = previous_amount.toSealedValue(),
                currency = previous_currency.toSealedValue(),
                timestamp = previous_timestamp.toSealedValue(),
                beneficiaries = previous_beneficiaries.map { beneficiary ->
                    OneTimeExpenseBeneficiary(
                        userUuid = beneficiary.user_uuid,
                        share = beneficiary.share.toSealedValue(),
                        isShareFixed = beneficiary.is_share_fixed.toSealedValue(),
                        sharePercentage = beneficiary.share_percentage.toSealedValue(),
                        shareWeight = beneficiary.share_weight.toSealedValue(),
                    )
                },
            ),
        )

        return copy(
            previous_signature = assertNotNull(
                sealBound(
                    key = expenseKey,
                    context = "one_time_expense:signature",
                    plaintext = """{"revision":1,"signature":"${Base64.Default.encode(signature)}"}""".encodeToByteArray(),
                ),
            ),
        )
    }

    /** The form and the writes put it right, so only a client of its own would seal this. */
    @Test
    fun `an expense whose amounts are written any other way does not open`() {
        val expense = expense()

        val anotherWay = listOf(
            expense.copy(amount = "20"),
            expense.copy(amount = "20.0"),
            expense.copy(beneficiaries = expense.beneficiaries.map { it.copy(share = it.share.substringBefore('.')) }),
        )

        anotherWay.forEach { assertNull(it.signedBy(creditor, revision = 1).open(), it.amount) }
    }

    @Test
    fun `shares set as weights open with them`() {
        val opened = assertNotNull(
            expense(bobShareWeight = "3", carolShareWeight = "2").signedBy(creditor, revision = 1).open(),
        )

        assertEquals(
            mapOf(BOB to "3", CAROL to "2"),
            opened.expense.beneficiaries.associate { it.userUuid to it.shareWeight },
        )
    }

    /** Sealing hides who pays a weight but not how long what it seals is, which would say it as well. */
    @Test
    fun `a weight of any length and none seal to the same length`() {
        val lengths = listOf(null, "1", "12.5", "100.00").flatMap { weight ->
            expense(bobShareWeight = weight).signedBy(creditor, revision = 1).previous_beneficiaries
                .map { Base64.Default.decode(it.share_weight.ciphertext).size }
        }

        assertEquals(1, lengths.toSet().size)
    }

    /** It would show one thing and charge another, and an edit would work the shares out again from it. */
    @Test
    fun `shares their weights do not come to do not open`() {
        assertNull(expense(bobShareWeight = "1", carolShareWeight = "1").signedBy(creditor, revision = 1).open())
    }

    @Test
    fun `weights swapped between beneficiaries do not open`() {
        val entry = expense(bobShareWeight = "3", carolShareWeight = "2").signedBy(creditor, revision = 1)
        val (first, second) = entry.previous_beneficiaries

        val swapped = entry.copy(
            previous_beneficiaries = listOf(
                first.copy(share_weight = second.share_weight),
                second.copy(share_weight = first.share_weight),
            ),
        )

        assertNull(swapped.open())
    }

    @Test
    fun `an expense opens in the currency it was sealed in`() {
        val won = CurrencyCatalog.defaultCurrencies.single { it.name == "KRW" }

        val opened = assertNotNull(expense(currency = won).signedBy(creditor, revision = 1).open())

        assertEquals(won, opened.expense.currency)
    }

    @Test
    fun `an expense in a currency the app does not ship with does not open`() {
        val unknown = CurrencyCatalog.defaultCurrency.copy(uuid = "33333333-3333-3333-3333-333333333333")

        assertNull(expense(currency = unknown).signedBy(creditor, revision = 1).open())
    }

    /** Sealing hides which currency an expense is in but not how long that is, which would say it as well. */
    @Test
    fun `every currency seals to the same length`() {
        val lengths = CurrencyCatalog.defaultCurrencies.map { currency ->
            Base64.Default.decode(expense(currency = currency).signedBy(creditor, revision = 1).previous_currency.ciphertext).size
        }

        assertEquals(1, lengths.toSet().size)
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

    private fun expense(
        title: String = "Pizzas",
        description: String = "",
        isBobShareFixed: Boolean = false,
        bobSharePercentage: String? = null,
        bobShareWeight: String? = null,
        carolShareWeight: String? = null,
        currency: Currency = CurrencyCatalog.defaultCurrency,
    ) = ExpenseWithBeneficiaries(
        uuid = EXPENSE,
        title = title,
        timestamp = "2026-10-12T09:00:00",
        description = description,
        amount = "20",
        creditorUuid = CREDITOR,
        currency = currency,
        beneficiaries = listOf(
            Beneficiary(
                expenseUuid = EXPENSE,
                userUuid = BOB,
                share = "12",
                isShareFixed = isBobShareFixed,
                sharePercentage = bobSharePercentage,
                shareWeight = bobShareWeight,
            ),
            Beneficiary(
                expenseUuid = EXPENSE,
                userUuid = CAROL,
                share = "8",
                isShareFixed = false,
                shareWeight = carolShareWeight,
            ),
        ),
    ).let { assertNotNull(it.withCanonicalAmounts()) }

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
