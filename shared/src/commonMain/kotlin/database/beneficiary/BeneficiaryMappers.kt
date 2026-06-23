package database.beneficiary

fun BeneficiaryEntity.toBeneficiary() = Beneficiary(
    uuid = uuid,
    expenseUuid = expenseUuid,
    userUuid = userUuid,
    share = share,
    isShareFixed = isShareFixed,
)

fun Beneficiary.toBeneficiaryEntity() = BeneficiaryEntity(
    uuid = uuid,
    expenseUuid = expenseUuid,
    userUuid = userUuid,
    share = share,
    isShareFixed = isShareFixed,
)
