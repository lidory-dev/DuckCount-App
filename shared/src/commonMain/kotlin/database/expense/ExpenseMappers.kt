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
    isRefund = isRefund,
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
        isRefund = expense.isRefund,
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
            isRefund = isRefund,
            currency = currency,
            groupUuid = groupUuid,
            isDeleted = isDeleted,
        ).toExpenseEntity(),
        beneficiaries = beneficiaries.map { it.toBeneficiaryEntity() },
    )
