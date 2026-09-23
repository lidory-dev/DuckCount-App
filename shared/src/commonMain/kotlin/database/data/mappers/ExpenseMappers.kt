package database.data.mappers

import database.data.local.entity.ExpenseEntity
import database.data.local.entity.ExpenseWithBeneficiariesEntity
import database.domain.Expense
import database.domain.ExpenseWithBeneficiaries

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
