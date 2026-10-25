package mls

import uniffi.lib_duckcount.InviteeKeyPackage
import uniffi.lib_duckcount.MlsClient

/**
 * A key package of this client's, as the server hands one out for it: a one-time one, or the
 * last-resort one when [isLastResort].
 */
internal fun MlsClient.inviteeKeyPackage(isLastResort: Boolean = false) = InviteeKeyPackage(
    userUuid = userUuid(),
    keyPackage = if (isLastResort) {
        generateKeyPackages(oneTimeCount = 0u).lastResortKeyPackage
    } else {
        generateKeyPackages(oneTimeCount = 1u).oneTimeKeyPackages.single()
    },
)
