package database.data.mappers

import database.data.local.entity.BeneficiaryEntity
import database.domain.Beneficiary

fun BeneficiaryEntity.toBeneficiary() = Beneficiary(
    expenseUuid = expenseUuid,
    userUuid = userUuid,
    share = share,
    isShareFixed = isShareFixed,
    sharePercentage = sharePercentage,
    shareWeight = shareWeight,
)

fun Beneficiary.toBeneficiaryEntity() = BeneficiaryEntity(
    expenseUuid = expenseUuid,
    userUuid = userUuid,
    share = share,
    isShareFixed = isShareFixed,
    sharePercentage = sharePercentage,
    shareWeight = shareWeight,
)

