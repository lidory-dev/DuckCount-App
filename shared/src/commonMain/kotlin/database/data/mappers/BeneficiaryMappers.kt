package database.data.mappers

import database.data.local.entity.BeneficiaryEntity
import database.data.remote.BeneficiaryDto
import database.domain.Beneficiary

// TODO: For now isShareFixed is a client-only concept the server does not carry, so it
// defaults to false on the way in. Revisit when encryption/metadata lands.
fun BeneficiaryDto.toBeneficiaryEntity(expenseUuid: String) = BeneficiaryEntity(
    expenseUuid = expenseUuid,
    userUuid = user_uuid,
    share = "50.0", // TODO: share,
    isShareFixed = false,
)

fun BeneficiaryEntity.toBeneficiary() = Beneficiary(
    expenseUuid = expenseUuid,
    userUuid = userUuid,
    share = share,
    isShareFixed = isShareFixed,
)

fun Beneficiary.toBeneficiaryEntity() = BeneficiaryEntity(
    expenseUuid = expenseUuid,
    userUuid = userUuid,
    share = share,
    isShareFixed = isShareFixed,
)

fun Beneficiary.toBeneficiaryDto() = BeneficiaryDto(
    user_uuid = userUuid,
    share = placeholderEncryptedFieldDto(), // TODO.
    is_share_fixed = placeholderEncryptedFieldDto(), // TODO.
)
