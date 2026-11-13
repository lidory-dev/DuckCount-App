package expense

import group.GroupExpenseHistoryError
import group.GroupExpenseWriteError
import mls.MlsIdentityError
import util.NetworkError
import vault.VaultError

/**
 * Why writing, deleting, restoring or marking an expense failed, at the granularity the iOS screens
 * tell apart, as Android's `ExpenseWriteError`, which lives in an Android-only module.
 */
enum class ExpenseWriteFailure {
    /** No connectivity. */
    NO_INTERNET,

    /** The server took too long to answer. */
    TIMEOUT,

    /** The server is unreachable. */
    SERVER_UNREACHABLE,

    /** The server rejected the request contents. */
    INVALID_DATA,

    /** The expense had every edit its server allows. */
    CONFLICT,

    /** The session is no longer valid. */
    UNAUTHORIZED,

    /**
     * This device does not hold the key to the expense being edited or marked: re-sealing it under
     * a new key would leave everyone it is shared with unable to open it, so it clears itself once
     * the key arrives.
     */
    KEY_MISSING,

    /** Another member changed the group expense in the meantime: the device shows their version. */
    CHANGED_MEANWHILE,

    /** The user is no longer a member of the expense's group. */
    GROUP_LEFT,

    /** The expense's group is still being created on the server. */
    CREATION_PENDING,

    /** The expense's group waits for its owner to come online. */
    AWAITING_OWNER,

    /** The server took too many changes from the user. */
    RATE_LIMITED,

    /** Anything else. */
    UNKNOWN,
}

/** Why saving the expense form failed, as Android's `ExpenseSaveError` tells it. */
sealed interface ExpenseSaveFailure {

    /** For a reason told in a sentence of its own. */
    data class Write(val failure: ExpenseWriteFailure) : ExpenseSaveFailure

    /** The user is no longer a member of the group expense's group. */
    data object GroupLeft : ExpenseSaveFailure

    /**
     * The server refused the expense for naming people who deleted their account, [beneficiaries],
     * in the order the form lists them: every one a new expense names, and only the ones an edit
     * adds. The form takes them out of its selection for the user to save it again.
     */
    data class BeneficiariesDeleted(val beneficiaries: List<DeletedBeneficiary>) : ExpenseSaveFailure
}

/** Somebody who deleted their account, by the name this device shows for them, [fullName], if any. */
data class DeletedBeneficiary(val userUuid: String, val fullName: String?)

/** Why the history of an expense could not be read, as Android's `ExpenseHistoryError` tells it. */
enum class ExpenseHistoryFailure {
    NO_INTERNET,
    TIMEOUT,
    SERVER_UNREACHABLE,

    /** Anything else: nothing on this device can open it, the server refused, … */
    UNKNOWN,
}

/**
 * Why a one-time expense write failed, as Android's `OneTimeExpenseWriteError.toExpenseWriteError`
 * tells it. A vault that could not be reached is told apart like the server itself, whether it was
 * read for the expense or to open the user's identity, and a missing root key like a session that
 * is gone; the rest is unknown, there being nothing the user can do differently about it.
 */
internal fun OneTimeExpenseWriteError.toWriteFailure(isOnline: Boolean): ExpenseWriteFailure =
    when (this) {
        is OneTimeExpenseWriteError.Network -> error.toWriteFailure(isOnline = isOnline)

        OneTimeExpenseWriteError.KeyMissing -> ExpenseWriteFailure.KEY_MISSING

        OneTimeExpenseWriteError.NoSession -> ExpenseWriteFailure.UNAUTHORIZED

        // Creating or editing reads the key ring from the server before the expense is sent, so
        // this is usually where being offline shows.
        is OneTimeExpenseWriteError.Vault -> error.toWriteFailure(isOnline = isOnline)

        is OneTimeExpenseWriteError.Identity -> when (val identityError = error) {
            // Opening the identity reads the vault from the server until it first opens in the
            // process, so a write made while the server has been out of reach fails here first.
            is MlsIdentityError.Vault -> identityError.error.toWriteFailure(isOnline = isOnline)

            MlsIdentityError.MissingSession -> ExpenseWriteFailure.UNAUTHORIZED

            MlsIdentityError.Crypto -> ExpenseWriteFailure.UNKNOWN
        }

        // Told by name by toSaveFailure, for the form, the one place it comes up.
        is OneTimeExpenseWriteError.BeneficiariesDeleted -> ExpenseWriteFailure.INVALID_DATA

        OneTimeExpenseWriteError.Crypto -> ExpenseWriteFailure.UNKNOWN
    }

private fun VaultError.toWriteFailure(isOnline: Boolean): ExpenseWriteFailure = when (this) {
    is VaultError.Network -> error.toWriteFailure(isOnline = isOnline)

    // Only ever unsealed during a registration or a login, so logging in again is the only way back.
    VaultError.MissingRootKey -> ExpenseWriteFailure.UNAUTHORIZED

    VaultError.Contended,
    VaultError.Crypto,
    VaultError.MalformedKeyRing,
    VaultError.MalformedContactBook,
    VaultError.MalformedMemberProfileBook,
    VaultError.MalformedBlockList,
    VaultError.OrphanedEntry,
        -> ExpenseWriteFailure.UNKNOWN
}

/**
 * Why saving the form failed: who deleted their account, by the name [fullNameOf] reads for each
 * of them, or else as [toWriteFailure] tells it.
 */
internal suspend fun OneTimeExpenseWriteError.toSaveFailure(
    isOnline: Boolean,
    fullNameOf: suspend (userUuid: String) -> String?,
): ExpenseSaveFailure =
    when (this) {
        is OneTimeExpenseWriteError.BeneficiariesDeleted -> ExpenseSaveFailure.BeneficiariesDeleted(
            beneficiaries = userUuids.map { DeletedBeneficiary(userUuid = it, fullName = fullNameOf(it)) },
        )

        else -> ExpenseSaveFailure.Write(failure = toWriteFailure(isOnline = isOnline))
    }

/**
 * The reason told for a failed request, as Android's `NetworkError.toExpenseWriteError` tells it.
 * The "couldn't connect" case is resolved against the device's connectivity ([isOnline]): Ktor's
 * Darwin engine reports a phone in airplane mode as a connection that failed.
 */
internal fun NetworkError.toWriteFailure(isOnline: Boolean): ExpenseWriteFailure = when (this) {
    NetworkError.NO_INTERNET -> ExpenseWriteFailure.NO_INTERNET
    NetworkError.REQUEST_TIMEOUT -> ExpenseWriteFailure.TIMEOUT

    NetworkError.UNREACHABLE ->
        if (isOnline) ExpenseWriteFailure.SERVER_UNREACHABLE else ExpenseWriteFailure.NO_INTERNET

    NetworkError.BAD_REQUEST,
    NetworkError.UNPROCESSABLE_ENTITY,
        -> ExpenseWriteFailure.INVALID_DATA

    NetworkError.CONFLICT -> ExpenseWriteFailure.CONFLICT

    NetworkError.UNAUTHORIZED -> ExpenseWriteFailure.UNAUTHORIZED

    NetworkError.NOT_FOUND,
    NetworkError.PAYLOAD_TOO_LARGE,
    NetworkError.TOO_MANY_REQUESTS,
    NetworkError.SERVER_ERROR,
    NetworkError.SERIALIZATION,
    NetworkError.FORBIDDEN,
    NetworkError.UNKNOWN,
        -> ExpenseWriteFailure.UNKNOWN
}

/**
 * Why the history of a one-time expense could not be read, as Android's
 * `OneTimeExpenseHistoryError.toExpenseHistoryError` tells it.
 */
internal fun OneTimeExpenseHistoryError.toHistoryFailure(isOnline: Boolean): ExpenseHistoryFailure =
    when (this) {
        is OneTimeExpenseHistoryError.Network -> when (error) {
            NetworkError.NO_INTERNET -> ExpenseHistoryFailure.NO_INTERNET
            NetworkError.REQUEST_TIMEOUT -> ExpenseHistoryFailure.TIMEOUT

            NetworkError.UNREACHABLE ->
                if (isOnline) ExpenseHistoryFailure.SERVER_UNREACHABLE else ExpenseHistoryFailure.NO_INTERNET

            else -> ExpenseHistoryFailure.UNKNOWN
        }

        OneTimeExpenseHistoryError.Unreadable -> ExpenseHistoryFailure.UNKNOWN
    }

/**
 * Why a group expense write failed, as Android's `GroupExpenseWriteError.toExpenseWriteError` tells
 * it: the identity, where being offline shows first, as for a one-time expense; another member's
 * edit made first, a group the user is no longer in, a creation the group waits on, the server's
 * limit and a group waiting for its owner each with their own wording; an expense that had every
 * edit it takes as a one-time expense's; and what the screens never offer as unknown.
 */
internal fun GroupExpenseWriteError.toWriteFailure(isOnline: Boolean): ExpenseWriteFailure =
    when (this) {
        is GroupExpenseWriteError.Network -> error.toWriteFailure(isOnline = isOnline)

        is GroupExpenseWriteError.Identity -> OneTimeExpenseWriteError.Identity(error).toWriteFailure(isOnline = isOnline)

        GroupExpenseWriteError.NoSession -> ExpenseWriteFailure.UNAUTHORIZED

        GroupExpenseWriteError.NotMember -> ExpenseWriteFailure.GROUP_LEFT

        GroupExpenseWriteError.Conflict -> ExpenseWriteFailure.CHANGED_MEANWHILE

        GroupExpenseWriteError.EditLimitReached -> ExpenseWriteFailure.CONFLICT

        GroupExpenseWriteError.CreationPending -> ExpenseWriteFailure.CREATION_PENDING

        GroupExpenseWriteError.AwaitingOwner -> ExpenseWriteFailure.AWAITING_OWNER

        is GroupExpenseWriteError.RateLimited -> ExpenseWriteFailure.RATE_LIMITED

        GroupExpenseWriteError.SettlementLocked,
        GroupExpenseWriteError.NotShown,
        GroupExpenseWriteError.Crypto,
        GroupExpenseWriteError.KeyNotCurrent,
            -> ExpenseWriteFailure.UNKNOWN
    }

/** Why saving the form of a group expense failed, as Android's `toExpenseSaveError` tells it. */
internal fun GroupExpenseWriteError.toSaveFailure(isOnline: Boolean): ExpenseSaveFailure =
    when (this) {
        GroupExpenseWriteError.NotMember -> ExpenseSaveFailure.GroupLeft
        else -> ExpenseSaveFailure.Write(failure = toWriteFailure(isOnline = isOnline))
    }

/**
 * Why the history of a group expense could not be read, as Android's
 * `GroupExpenseHistoryError.toExpenseHistoryError` tells it: the identity, which reads the vault
 * from the server until it first opens in the process, failing on the network is told as the
 * network failing.
 */
internal fun GroupExpenseHistoryError.toHistoryFailure(isOnline: Boolean): ExpenseHistoryFailure =
    when (this) {
        is GroupExpenseHistoryError.Network -> OneTimeExpenseHistoryError.Network(error).toHistoryFailure(isOnline = isOnline)

        is GroupExpenseHistoryError.Identity -> when (val identityError = error) {
            is MlsIdentityError.Vault -> when (val vaultError = identityError.error) {
                is VaultError.Network -> OneTimeExpenseHistoryError.Network(vaultError.error).toHistoryFailure(isOnline = isOnline)

                else -> ExpenseHistoryFailure.UNKNOWN
            }

            MlsIdentityError.MissingSession,
            MlsIdentityError.Crypto,
                -> ExpenseHistoryFailure.UNKNOWN
        }

        GroupExpenseHistoryError.NoSession,
        GroupExpenseHistoryError.NotShown,
        GroupExpenseHistoryError.NotMember,
        GroupExpenseHistoryError.Storage,
            -> ExpenseHistoryFailure.UNKNOWN
    }
