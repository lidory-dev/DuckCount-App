package app.lidory.duckcount.groupList

import app.lidory.duckcount.groupList.model.Beneficiary
import app.lidory.duckcount.groupList.model.Currency
import app.lidory.duckcount.groupList.model.ExpenseWithBeneficiaries
import app.lidory.duckcount.groupList.model.GroupMember
import database.domain.Beneficiary as DBBeneficiary
import database.domain.Currency as DBCurrency
import database.domain.ExpenseWithBeneficiaries as DBExpenseWithBeneficiaries
import database.domain.GroupMember as DBGroupMember

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

fun DBCurrency.toGroupListModel(): Currency {
    return Currency(
        uuid = uuid,
        name = name,
        symbol = symbol,
        hasSubunits = hasSubunits,
    )
}

fun Currency.toDb(): DBCurrency {
    return DBCurrency(
        uuid = uuid,
        name = name,
        symbol = symbol,
        hasSubunits = hasSubunits,
    )
}

fun DBBeneficiary.toGroupListModel(): Beneficiary {
    return Beneficiary(
        uuid = uuid,
        expenseUuid = expenseUuid,
        userUuid = userUuid,
        share = share,
        isShareFixed = isShareFixed,
    )
}

fun Beneficiary.toDb(): DBBeneficiary {
    return DBBeneficiary(
        uuid = uuid,
        expenseUuid = expenseUuid,
        userUuid = userUuid,
        share = share,
        isShareFixed = isShareFixed,
    )
}

fun GroupMember.toDb(): DBGroupMember {
    return DBGroupMember(
        userUuid = userUuid,
        groupUuid = groupUuid,
        isAdmin = isAdmin,
    )
}
