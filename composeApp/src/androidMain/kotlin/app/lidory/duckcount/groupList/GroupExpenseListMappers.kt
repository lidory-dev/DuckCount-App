package app.lidory.duckcount.groupList

import app.lidory.duckcount.groupList.model.Beneficiary
import app.lidory.duckcount.groupList.model.ExpenseWithBeneficiaries
import database.domain.Beneficiary as DBBeneficiary
import database.domain.ExpenseWithBeneficiaries as DBExpenseWithBeneficiaries

fun DBExpenseWithBeneficiaries.toGroupListModel(): ExpenseWithBeneficiaries {
    return ExpenseWithBeneficiaries(
        uuid = uuid,
        timestamp = timestamp,
        title = title,
        description = description,
        amount = amount,
        creditorUuid = creditorUuid,
        isSettlement = isSettlement,
        currency = currency.toGroupListModel(),
        beneficiaries = beneficiaries.map { it.toGroupListModel() },
        groupUuid = groupUuid,
        isDeleted = isDeleted,
    )
}

fun ExpenseWithBeneficiaries.toDb(): DBExpenseWithBeneficiaries {
    return DBExpenseWithBeneficiaries(
        uuid = uuid,
        timestamp = timestamp,
        title = title,
        description = description,
        amount = amount,
        creditorUuid = creditorUuid,
        isSettlement = isSettlement,
        currency = currency.toDb(),
        beneficiaries = beneficiaries.map { it.toDb() },
        groupUuid = groupUuid,
        isDeleted = isDeleted,
    )
}

fun DBBeneficiary.toGroupListModel(): Beneficiary {
    return Beneficiary(
        uuid = uuid,
        expenseUuid = expenseUuid,
        userUuid = userUuid,
        share = share,
        isShareFixed = isShareFixed,
        sharePercentage = sharePercentage,
    )
}

fun Beneficiary.toDb(): DBBeneficiary {
    return DBBeneficiary(
        uuid = uuid,
        expenseUuid = expenseUuid,
        userUuid = userUuid,
        share = share,
        isShareFixed = isShareFixed,
        sharePercentage = sharePercentage,
    )
}
