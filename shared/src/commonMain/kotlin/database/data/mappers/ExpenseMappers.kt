package database.data.mappers

import database.data.local.entity.ExpenseEntity
import database.data.local.entity.ExpenseWithBeneficiariesEntity
import database.domain.Expense
import database.domain.ExpenseWithBeneficiaries
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

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

@OptIn(ExperimentalTime::class)
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
        isSettled = settledMark?.isSettled ?: false,
        settledAt = settledMark?.takeIf { it.isSettled }?.let {
            Instant.fromEpochMilliseconds(it.markedAt).toLocalDateTime(TimeZone.currentSystemDefault())
        },
        revision = expense.revision,
        deletedByUuid = deletion?.takeIf { it.isDeleted }?.authorUuid,
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
