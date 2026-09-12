package database.data.mappers

import database.data.local.entity.ExpenseEntity
import database.data.local.entity.ExpenseWithBeneficiariesEntity
import database.data.remote.EncryptedFieldDto
import database.data.remote.ExpenseDto
import database.data.remote.OneTimeExpenseRequestBody
import database.domain.Currency
import database.domain.CurrencyCatalog
import database.domain.Expense
import database.domain.ExpenseWithBeneficiaries

// --- Placeholders until end-to-end encryption is wired up -------------------
// The server has no `currency` for one-time expenses, and `is_settled` /
// `master_key` are encrypted `{ field, nonce }` objects. Until real crypto and
// currency handling land, we default the currency, stash the settle boolean in
// the `field` as plain text, and send an empty master key.

private val DEFAULT_CURRENCY = CurrencyCatalog.defaultCurrency

fun placeholderEncryptedFieldDto() =
    EncryptedFieldDto(ciphertext = "placeholder", nonce = "nonce")

private fun fakeBoolean(): Boolean = false

private fun fakeTimestamp(): String = "2026-07-21T00:11:34.161895"

fun fakeTitle(): String = "Fake Title"

fun fakeDescription(): String = "Fake Description"

fun fakeCurrency(): Currency = DEFAULT_CURRENCY

private fun fakeAmount(): String = "100.00"
// ---------------------------------------------------------------------------

fun ExpenseDto.toExpenseWithBeneficiariesEntity() =
    ExpenseWithBeneficiariesEntity(
        expense = Expense(
            uuid = uuid,
            timestamp = fakeTimestamp(),
            title = fakeTitle(),
            description = fakeDescription(),
            amount = fakeAmount(),
            creditorUuid = creditor_uuid,
            isSettlement = fakeBoolean(),
            currency = DEFAULT_CURRENCY,
            groupUuid = null,
            isDeleted = false,
        ).toExpenseEntity(),
        beneficiaries = beneficiaries.map {
            it.toBeneficiaryEntity(expenseUuid = uuid)
        },
    )

fun ExpenseWithBeneficiaries.toOneTimeRequestBody() =
    OneTimeExpenseRequestBody(
        master_key = placeholderEncryptedFieldDto(),
        title = placeholderEncryptedFieldDto(),
        description = placeholderEncryptedFieldDto(),
        amount = placeholderEncryptedFieldDto(),
        timestamp = placeholderEncryptedFieldDto(),
        is_settled = false, // TODO.
        beneficiaries = beneficiaries.map { it.toBeneficiaryDto() },
    )

fun Expense.toExpenseEntity() = ExpenseEntity(
    uuid = uuid,
    timestamp = timestamp,
    title = title,
    description = description,
    amount = amount,
    creditorUuid = creditorUuid,
    isSettlement = isSettlement,
    currency = currency,
    groupUuid = groupUuid,
    isDeleted = isDeleted,
)


fun ExpenseEntity.toExpense() = Expense(
    uuid = uuid,
    timestamp = timestamp,
    title = title,
    description = description,
    amount = amount,
    creditorUuid = creditorUuid,
    isSettlement = isSettlement,
    currency = currency,
    groupUuid = groupUuid,
    isDeleted = isDeleted,
)

fun ExpenseWithBeneficiariesEntity.toExpenseWithBeneficiaries() =
    ExpenseWithBeneficiaries(
        uuid = expense.uuid,
        timestamp = expense.timestamp,
        title = expense.title,
        description = expense.description,
        amount = expense.amount,
        creditorUuid = expense.creditorUuid,
        isSettlement = expense.isSettlement,
        currency = expense.currency,
        groupUuid = expense.groupUuid,
        isDeleted = expense.isDeleted,
        beneficiaries = beneficiaries.map { it.toBeneficiary() },
    )

fun ExpenseWithBeneficiaries.toExpenseWithBeneficiariesEntity() =
    ExpenseWithBeneficiariesEntity(
        expense = Expense(
            uuid = uuid,
            timestamp = timestamp,
            title = title,
            description = description,
            amount = amount,
            creditorUuid = creditorUuid,
            isSettlement = isSettlement,
            currency = currency,
            groupUuid = groupUuid,
            isDeleted = isDeleted,
        ).toExpenseEntity(),
        beneficiaries = beneficiaries.map { it.toBeneficiaryEntity() },
    )
