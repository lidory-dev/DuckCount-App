package database.expense

import database.beneficiary.toBeneficiary
import database.beneficiary.toBeneficiaryEntity

fun Expense.toExpenseEntity() = ExpenseEntity(
    uuid = uuid,
    timestamp = timestamp,
    title = title,
    description = description,
    amount = amount,
    creditorUuid = creditorUuid,
    isRefund = isRefund,
    currency = currency,
)


fun ExpenseEntity.toExpense() = Expense(
    uuid = uuid,
    timestamp = timestamp,
    title = title,
    description = description,
    amount = amount,
    creditorUuid = creditorUuid,
    isRefund = isRefund,
    currency = currency,
)

fun ExpenseWithBeneficiariesEntity.toExpenseWithBeneficiaries() =
    ExpenseWithBeneficiaries(
        uuid = expense.uuid,
        timestamp = expense.timestamp,
        title = expense.title,
        description = expense.description,
        amount = expense.amount,
        creditorUuid = expense.creditorUuid,
        isRefund = expense.isRefund,
        currency = expense.currency,
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
            isRefund = isRefund,
            currency = currency,
        ).toExpenseEntity(),
        beneficiaries = beneficiaries.map { it.toBeneficiaryEntity() },
    )
