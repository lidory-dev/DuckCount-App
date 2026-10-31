package group

import app.lidory.duckcount.ExpenseLimits
import database.data.local.entity.ExpenseWithBeneficiariesEntity
import database.data.local.entity.GroupEntity
import database.data.remote.EncryptedFieldDto
import database.domain.ExpenseWithBeneficiaries
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mls.InMemoryMlsStore
import uniffi.lib_duckcount.MlsClient
import uniffi.lib_duckcount.generateSymmetricEncryptionKey
import util.Result
import vault.sealBound
import vault.unsealBound
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private const val ALICE = "11111111-1111-1111-1111-111111111111"
private const val BOB = "22222222-2222-2222-2222-222222222222"
private const val CAROL = "33333333-3333-3333-3333-333333333333"
private const val MALLORY = "77777777-7777-7777-7777-777777777777"

private const val GROUP = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val OTHER_GROUP = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

private const val EXPENSE = "eeeeeeee-eeee-8eee-8eee-eeeeeeeeeeee"
private const val OTHER_EXPENSE = "ffffffff-ffff-8fff-8fff-ffffffffffff"

/** What the server caps the content of a version at, `MAX_GROUP_EXPENSE_CONTENT_LENGTH` in the API. */
private const val MAX_GROUP_EXPENSE_CONTENT_LENGTH = 32 * 1024

/**
 * What a version of a group expense, sealed and signed by its author, opens to on another member's
 * device: what was sealed, as that version of that expense of that group, signed by whoever the key
 * this device holds for them says, and saying what the app writes, see [GroupExpenseOpener]; and
 * nothing else. Every seal and signature is the library's own.
 */
class GroupExpenseSealingTest {

    private val alice = MlsClient(userUuid = ALICE, secret = ByteArray(32) { 1 }, store = InMemoryMlsStore())
    private val bob = MlsClient(userUuid = BOB, secret = ByteArray(32) { 2 }, store = InMemoryMlsStore())
    private val carol = MlsClient(userUuid = CAROL, secret = ByteArray(32) { 3 }, store = InMemoryMlsStore())
    private val mallory = MlsClient(userUuid = MALLORY, secret = ByteArray(32) { 7 }, store = InMemoryMlsStore())

    private val groupKey = generateSymmetricEncryptionKey()

    /** Alice paid 90.00, 30.00 of it for herself and 60.00 for Bob. */
    private val dinner = groupExpenseOf(
        uuid = EXPENSE,
        groupUuid = GROUP,
        creditorUuid = ALICE,
        amount = "90.00",
        shares = listOf(ALICE to "30.00", BOB to "60.00"),
    )

    /** Bob's device, which pinned Alice as a contact: the one reading what she writes. */
    private val reader = bob

    init {
        bob.pinIdentity(userUuid = ALICE, signatureKey = alice.signaturePublicKey())
    }

    /** What Bob's device makes of [content], read as [revision] of [expenseUuid] in [groupUuid], with [authorKey] for its author. */
    private fun open(
        content: EncryptedFieldDto,
        revision: Long = 1,
        groupUuid: String = GROUP,
        expenseUuid: String = EXPENSE,
        authorKey: ByteArray? = alice.signaturePublicKey(),
    ): OpenedGroupExpense? =
        content.unsealGroupExpense(groupKey = groupKey, groupUuid = groupUuid, expenseUuid = expenseUuid, revision = revision)
            ?.takeIf { it.isSignedBy(authorKey = authorKey) }
            ?.opened(currency = GROUP_CURRENCY)

    private fun sealed(
        expense: ExpenseWithBeneficiaries = dinner,
        revision: Long = 1,
        author: MlsClient = alice,
        authorUuid: String = ALICE,
    ): EncryptedFieldDto = assertNotNull(
        expense.sealGroupExpense(groupUuid = GROUP, revision = revision, groupKey = groupKey, client = author, authorUuid = authorUuid),
    )

    @Test
    fun `a version opens to what its author sealed`() {
        val opened = assertNotNull(open(content = sealed(revision = 3), revision = 3))

        assertEquals(dinner.asWritten(), opened.expense.asWritten())
        assertEquals(3L, opened.revision)
        assertEquals(ALICE, opened.authorUuid)
    }

    /** As the server serving a version of one expense as another's would leave a member. */
    @Test
    fun `a version does not open as another expense's or another group's`() {
        assertNull(open(content = sealed(), expenseUuid = OTHER_EXPENSE))
        assertNull(open(content = sealed(), groupUuid = OTHER_GROUP))
    }

    @Test
    fun `a version does not open under another key`() {
        val content = sealed()

        assertNull(content.unsealGroupExpense(generateSymmetricEncryptionKey(), GROUP, EXPENSE, revision = 1))
    }

    /** As the server listing an older version at a newer revision would, to have it shown over a later one. */
    @Test
    fun `a version listed at another revision than its author signed does not verify`() {
        assertNull(open(content = sealed(revision = 2), revision = 3))
        assertNull(open(content = sealed(revision = 2), revision = 1))
    }

    /**
     * As a member, who holds the group's key, opening Alice's version, changing what it says and
     * sealing it again would: every field of it is signed, so the signature no longer verifies.
     */
    @Test
    fun `a version whose content was changed after it was signed does not verify`() {
        val changes: Map<String, (JsonObject) -> JsonObject> = mapOf(
            "title" to { it.with("title", JsonPrimitive("Lunch")) },
            "description" to { it.with("description", JsonPrimitive("")) },
            "amount" to { it.with("amount", JsonPrimitive("900.00")) },
            "timestamp" to { it.with("timestamp", JsonPrimitive("2026-10-31T19:45:00")) },
            "creditor" to { it.with("creditor_uuid", JsonPrimitive(BOB)) },
            "share" to { it.withBeneficiary(1) { beneficiary -> beneficiary.with("share", JsonPrimitive("50.00")) } },
            "fixed share" to { it.withBeneficiary(1) { beneficiary -> beneficiary.with("is_share_fixed", JsonPrimitive(1)) } },
            "beneficiary" to { it.withBeneficiary(1) { beneficiary -> beneficiary.with("user_uuid", JsonPrimitive(CAROL)) } },
            "settlement" to { it.with("is_settlement", JsonPrimitive(1)) },
            "author" to { it.with("author_uuid", JsonPrimitive(BOB)) },
        )

        changes.forEach { (field, change) ->
            val tampered = sealed().tamperedWith(change = change)

            // Who it says wrote it is checked against the key held for them: Bob's, when it says Bob.
            val authorKey = if (field == "author") bob.signaturePublicKey() else alice.signaturePublicKey()

            assertNull(open(content = tampered, authorKey = authorKey), "a changed $field verified")
        }
    }

    /** As Bob, a member, writing a version and passing it off as Alice's. */
    @Test
    fun `a version another member signed does not pass for its author's`() {
        assertNull(open(content = sealed(author = bob, authorUuid = ALICE)))
    }

    /** Ten edits, every one an expense takes, and none past them, whoever signed it. */
    @Test
    fun `a version at the last revision opens and one past it does not`() {
        assertNotNull(open(content = sealed(revision = LAST_GROUP_EXPENSE_REVISION), revision = LAST_GROUP_EXPENSE_REVISION))

        listOf(0L, -1L, LAST_GROUP_EXPENSE_REVISION + 1, Long.MAX_VALUE).forEach { revision ->
            assertNull(open(content = sealed(revision = revision), revision = revision), "revision $revision opened")
            assertNull(open(content = sealed(revision = revision), revision = revision, authorKey = null), "revision $revision opened")
        }
    }

    @Test
    fun `a deletion flag opens to what its author sealed`() {
        listOf(true, false).forEach { isDeleted ->
            val flag = assertNotNull(openFlag(content = flagged(isDeleted = isDeleted, deletionRevision = 3), deletionRevision = 3))

            assertEquals(isDeleted, flag.deletion.isDeleted)
            assertEquals(3UL, flag.deletion.deletionRevision)
            assertEquals(ALICE, flag.authorUuid)
        }
    }

    /** As the server serving one expense's flag as another's, or a version as a flag, would leave a member. */
    @Test
    fun `a deletion flag opens as no other expense's nor as a version`() {
        assertNull(openFlag(content = flagged(), expenseUuid = OTHER_EXPENSE))
        assertNull(openFlag(content = flagged(), groupUuid = OTHER_GROUP))
        assertNull(openFlag(content = sealed()))
        assertNull(open(content = flagged()))
        assertNull(flagged().openGroupExpenseDeletion(generateSymmetricEncryptionKey(), GROUP, EXPENSE, deletionRevision = 1))
    }

    /** As the server listing the flag from before a restore at a newer revision would, to have it shown over a later one. */
    @Test
    fun `a deletion flag listed at another revision than its author signed does not verify`() {
        assertNull(openFlag(content = flagged(deletionRevision = 2), deletionRevision = 3))
        assertNull(openFlag(content = flagged(deletionRevision = 2), deletionRevision = 1))
        assertNull(openFlag(content = flagged(deletionRevision = 0), deletionRevision = 0, authorKey = null))
    }

    /** As a member, who holds the group's key, turning Alice's deletion into a restore, or into theirs. */
    @Test
    fun `a deletion flag whose content was changed after it was signed does not verify`() {
        val context = "group_expense_deletion:$GROUP:$EXPENSE"

        assertNull(openFlag(content = flagged().tamperedWith(context) { it.with("is_deleted", JsonPrimitive(0)) }))
        assertNull(
            openFlag(
                content = flagged().tamperedWith(context) { it.with("author_uuid", JsonPrimitive(BOB)) },
                authorKey = bob.signaturePublicKey(),
            ),
        )
        assertNull(openFlag(content = flagged(author = bob, authorUuid = ALICE)))
    }

    /**
     * AES-GCM does not hide how long what it seals is: a deletion and a restore are sealed to the
     * same length, so that the server cannot tell one from the other by the flag alone. Measured in
     * bytes: the Base64 the server is sent hides a byte more or less two times in three, not from
     * the server, which decodes it.
     */
    @Test
    fun `a deletion and a restore are sealed to the same length`() {
        assertEquals(flagged(isDeleted = true).sealedSize(), flagged(isDeleted = false, deletionRevision = 2).sealedSize())
    }

    /** How many bytes this seals, as the server holds them once decoded. */
    private fun EncryptedFieldDto.sealedSize(): Int = Base64.Default.decode(ciphertext).size

    /** Bob is who Alice paid back: he alone deletes it, and nobody restores it, held to his key or not. */
    @Test
    fun `a settlement's flag is its recipient's deletion alone`() {
        val shown = OpenedGroupExpense(expense = settlement(), revision = 1, authorUuid = BOB).toEntity()

        listOf(bob.signaturePublicKey(), null).forEach { bobsKey ->
            assertNotNull(
                openFlag(content = flagged(author = bob, authorUuid = BOB), authorKey = bobsKey, shown = shown),
                "Bob's deletion refused",
            )
            assertNull(
                openFlag(content = flagged(author = bob, authorUuid = BOB, isDeleted = false), authorKey = bobsKey, shown = shown),
                "Bob's restore opened",
            )
        }

        assertNull(openFlag(content = flagged(), authorKey = alice.signaturePublicKey(), shown = shown))
        assertNull(openFlag(content = flagged(), authorKey = null, shown = shown))
    }

    /** The user's own versions are checked against their own key, whatever the device recorded for them. */
    @Test
    fun `the user's own key is the first one a version is checked against`() = runBlocking<Unit> {
        val key = alice.authorKeyOf(authorUuid = ALICE, selfUuid = ALICE, groupUuid = GROUP) { _, _ ->
            mallory.signaturePublicKey()
        }

        assertTrue(key.contentEquals(alice.signaturePublicKey()))
    }

    /** A contact's pin is the identity this device holds them to everywhere, the group's tree included. */
    @Test
    fun `a contact's pin comes before the key the group's tree held for them`() = runBlocking<Unit> {
        val key = reader.authorKeyOf(authorUuid = ALICE, selfUuid = BOB, groupUuid = GROUP) { _, _ ->
            mallory.signaturePublicKey()
        }

        assertNotNull(open(content = sealed(), authorKey = key))
    }

    /** Carol is no contact of Bob's: the key the group's tree held for her is what her versions are held to. */
    @Test
    fun `a member who is no contact is held to the key the group's tree held for them`() = runBlocking<Unit> {
        val byCarol = sealed(author = carol, authorUuid = CAROL)

        val recorded = reader.authorKeyOf(authorUuid = CAROL, selfUuid = BOB, groupUuid = GROUP) { groupUuid, userUuid ->
            if (groupUuid == GROUP && userUuid == CAROL) carol.signaturePublicKey() else null
        }

        assertNotNull(open(content = byCarol, authorKey = recorded))

        // Mallory, another member, signing as Carol.
        assertNull(open(content = sealed(author = mallory, authorUuid = CAROL), authorKey = recorded))
    }

    /** A member who left before Bob joined, whose leaf his device never read: nothing to hold them to. */
    @Test
    fun `an author this device holds no key for is taken at the version's word`() = runBlocking<Unit> {
        val key = reader.authorKeyOf(authorUuid = CAROL, selfUuid = BOB, groupUuid = GROUP) { _, _ -> null }

        assertNull(key)
        assertNotNull(open(content = sealed(author = mallory, authorUuid = CAROL), authorKey = key))
    }

    /** Bob says Alice paid him back: he alone may, being who it was paid to. */
    @Test
    fun `a settlement opens when its recipient signed it`() {
        val opened = assertNotNull(
            open(content = sealed(expense = settlement(), author = bob, authorUuid = BOB), authorKey = bob.signaturePublicKey()),
        )

        assertTrue(opened.expense.isSettlement)
    }

    /** Alice saying she paid Bob back, which only Bob can say. */
    @Test
    fun `a settlement signed by anybody but its recipient does not open`() {
        assertNull(open(content = sealed(expense = settlement()), authorKey = alice.signaturePublicKey()))
    }

    /** Nothing to check her signature against, but whether she may sign it is no signature's to tell. */
    @Test
    fun `a settlement from an author this device holds no key for is refused unless they are its recipient`() {
        assertNull(open(content = sealed(expense = settlement()), authorKey = null))
        assertNotNull(open(content = sealed(expense = settlement(), author = bob, authorUuid = BOB), authorKey = null))
    }

    /**
     * Bob edits what he recorded of Alice paying him back, which nobody does: refused at any
     * revision but the first, held to his key or not, with no version before it to tell an edit by.
     * It unseals all the same, which tells the key it is sealed under from any other the chain of
     * links leads to.
     */
    @Test
    fun `a settlement past its first revision does not open even signed by its recipient`() {
        listOf(FIRST_GROUP_EXPENSE_REVISION + 1, LAST_GROUP_EXPENSE_REVISION).forEach { revision ->
            val content = sealed(expense = settlement(), revision = revision, author = bob, authorUuid = BOB)

            assertNotNull(
                content.unsealGroupExpense(groupKey = groupKey, groupUuid = GROUP, expenseUuid = EXPENSE, revision = revision),
                "revision $revision did not unseal",
            )

            listOf(bob.signaturePublicKey(), null).forEach { bobsKey ->
                assertNull(open(content = content, revision = revision, authorKey = bobsKey), "revision $revision opened")
            }
        }
    }

    /** One the screens could not lay out, sealed by a member whose app does not hold to the form. */
    @Test
    fun `a version saying what the app does not write does not open`() {
        val tooLong = dinner.copy(title = "a".repeat(ExpenseLimits.MAX_TITLE_LENGTH + 1))
        val zoned = dinner.copy(timestamp = "2026-10-30T19:45:00Z")
        val unbalanced = dinner.copy(amount = "100.00")

        listOf(tooLong, zoned, unbalanced).forEach { expense ->
            assertNull(open(content = sealed(expense = expense)), "opened: $expense")
        }
    }

    /**
     * The longest version the form can make: a hundred beneficiaries, each share the longest amount
     * there is, the longest title and description, each character one JSON writes six bytes for.
     */
    @Test
    fun `the longest version sealed is within what the server stores of one`() {
        val beneficiaryUuids = (0 until ExpenseLimits.MAX_BENEFICIARIES).map { index ->
            "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
        }

        val longest = groupExpenseOf(
            uuid = EXPENSE,
            groupUuid = GROUP,
            creditorUuid = ALICE,
            amount = "999999999999999.99",
            shares = beneficiaryUuids.map { it to "999999999999999.99" },
            title = "\u0001".repeat(ExpenseLimits.MAX_TITLE_LENGTH),
            description = "\u0001".repeat(ExpenseLimits.MAX_DESCRIPTION_LENGTH),
        ).let { expense -> expense.copy(beneficiaries = expense.beneficiaries.map { it.copy(isShareFixed = true) }) }

        val content = sealed(expense = longest, revision = Long.MAX_VALUE)

        assertTrue(
            content.ciphertext.length <= MAX_GROUP_EXPENSE_CONTENT_LENGTH,
            "${content.ciphertext.length} characters",
        )
    }

    @Test
    fun `the opener keeps a version sealed under a key it cannot have for later`() = runBlocking<Unit> {
        val opener = GroupExpenseOpener(
            group = groupEntity(),
            selfUuid = BOB,
            client = bob,
            keyOf = { _, _, _ -> Result.Error(GroupKeyChainError.NotHeld) },
            recorded = { _, _ -> null },
        )

        assertEquals(
            GroupExpenseOpening.Unreadable,
            opener.open(expenseUuid = EXPENSE, revision = 1, keyGeneration = 2, content = sealed()),
        )
    }

    @Test
    fun `the opener opens a version under the key of its generation`() = runBlocking<Unit> {
        val opener = GroupExpenseOpener(
            group = groupEntity(),
            selfUuid = BOB,
            client = bob,
            keyOf = { _, generation, _ -> if (generation == 1L) Result.Success(groupKey) else Result.Error(GroupKeyChainError.NotHeld) },
            recorded = { _, _ -> null },
        )

        val opening = opener.open(expenseUuid = EXPENSE, revision = 1, keyGeneration = 1, content = sealed())

        val opened = when (opening) {
            is GroupExpenseOpening.Opened -> opening.opened
            else -> fail("Not opened: $opening")
        }

        assertEquals(dinner.asWritten(), opened.expense.asWritten())
    }

    @Test
    fun `the opener opens a deletion flag under the key of its generation as it is stored`() = runBlocking<Unit> {
        val opener = GroupExpenseOpener(
            group = groupEntity(),
            selfUuid = BOB,
            client = bob,
            keyOf = { _, generation, _ -> if (generation == 1L) Result.Success(groupKey) else Result.Error(GroupKeyChainError.NotHeld) },
            recorded = { _, _ -> null },
        )

        val opening = opener.openDeletion(
            expenseUuid = EXPENSE,
            deletionRevision = 2,
            keyGeneration = 1,
            content = flagged(deletionRevision = 2),
            shown = shownDinner(),
        )

        val flag = when (opening) {
            is GroupExpenseOpening.Opened -> opening.opened
            else -> fail("Not opened: $opening")
        }

        assertEquals(EXPENSE, flag.expenseUuid)
        assertEquals(2L, flag.deletionRevision)
        assertTrue(flag.isDeleted)
        assertEquals(ALICE, flag.authorUuid)

        assertEquals(
            GroupExpenseOpening.Unreadable,
            opener.openDeletion(expenseUuid = EXPENSE, deletionRevision = 2, keyGeneration = 2, content = flagged(deletionRevision = 2), shown = shownDinner()),
        )
    }

    /** The deletion flag Bob's device makes of [content], read at [deletionRevision] of [expenseUuid] in [groupUuid], against [shown]. */
    private fun openFlag(
        content: EncryptedFieldDto,
        deletionRevision: Long = 1,
        groupUuid: String = GROUP,
        expenseUuid: String = EXPENSE,
        authorKey: ByteArray? = alice.signaturePublicKey(),
        shown: ExpenseWithBeneficiariesEntity = shownDinner(),
    ): SignedGroupExpenseDeletion? =
        content.openGroupExpenseDeletion(groupKey = groupKey, groupUuid = groupUuid, expenseUuid = expenseUuid, deletionRevision = deletionRevision)
            ?.takeIf { it.isSignedBy(authorKey = authorKey, shown = shown) }

    /** The deletion flag of the dinner saying [isDeleted] at [deletionRevision], set by [author] as [authorUuid]. */
    private fun flagged(
        isDeleted: Boolean = true,
        deletionRevision: Long = 1,
        author: MlsClient = alice,
        authorUuid: String = ALICE,
    ): EncryptedFieldDto = assertNotNull(
        sealGroupExpenseDeletion(
            groupUuid = GROUP,
            expenseUuid = EXPENSE,
            deletionRevision = deletionRevision,
            isDeleted = isDeleted,
            groupKey = groupKey,
            client = author,
            authorUuid = authorUuid,
        ),
    )

    /** The dinner as Bob's device shows it, created by Alice. */
    private fun shownDinner() = OpenedGroupExpense(expense = dinner, revision = 1, authorUuid = ALICE).toEntity()

    /** Alice paid Bob 90.00 back. */
    private fun settlement() = groupExpenseOf(
        uuid = EXPENSE,
        groupUuid = GROUP,
        creditorUuid = ALICE,
        amount = "90.00",
        shares = listOf(BOB to "90.00"),
        title = "",
        description = "",
        isSettlement = true,
    )

    private fun groupEntity() = GroupEntity(
        uuid = GROUP,
        title = "Ski trip",
        description = "",
        currency = GROUP_CURRENCY,
        infoRevision = FIRST_GROUP_REVISION,
        ownerUuid = ALICE,
        mlsGroupId = GROUP.encodeToByteArray(),
        groupKey = groupKey,
        keyGeneration = FIRST_KEY_GENERATION,
        isBackedUp = true,
        isLeft = false,
        handedProfileKeyHash = null,
    )

    /** This content opened, changed by [change] and sealed again under the group's key, bound to [context], as a member could. */
    private fun EncryptedFieldDto.tamperedWith(
        context: String = "group_expense:$GROUP:$EXPENSE",
        change: (JsonObject) -> JsonObject,
    ): EncryptedFieldDto {
        val opened = assertNotNull(unsealBound(key = groupKey, context = context, content = this)).decodeToString()
        val changed = change(Json.parseToJsonElement(opened).jsonObject)

        return assertNotNull(sealBound(key = groupKey, context = context, plaintext = changed.toString().encodeToByteArray()))
    }
}

/** This expense as it is written, its beneficiaries' UUIDs aside, which nothing but this device's memory holds. */
private fun ExpenseWithBeneficiaries.asWritten() = copy(beneficiaries = beneficiaries.map { it.copy(uuid = "") })

private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))

private fun JsonObject.withBeneficiary(index: Int, change: (JsonObject) -> JsonObject): JsonObject {
    val beneficiaries = getValue("beneficiaries").jsonArray.toMutableList()

    beneficiaries[index] = change(beneficiaries[index].jsonObject)

    return with("beneficiaries", JsonArray(beneficiaries))
}
