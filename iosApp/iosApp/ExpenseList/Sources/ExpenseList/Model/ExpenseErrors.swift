import Foundation

/// Why the form's amount and shares cannot be saved as they stand, as Android's form tells it.
public enum ExpenseValidationError: Hashable, Sendable {
    case noBeneficiaries

    /// The creditor alone shares the expense, which leaves nobody owing them anything.
    case noBeneficiaryOtherThanCreditor

    case tooManyBeneficiaries
    case sharesBelowTotalAmount
    case sharesExceedTotalAmount

    /// A share is missing, zero or below.
    case nonPositiveShare

    /// What a typed amount or share can still be: zero, or too long. Never let through: everybody an
    /// expense names refuses one that does not validate, and would never see it.
    case invalidAmount

    public var message: String {
        switch self {
        case .noBeneficiaries:
            String(localized: "An expense must have at least one beneficiary.", bundle: .module)
        case .noBeneficiaryOtherThanCreditor:
            String(localized: "An expense must have at least one beneficiary other than the creditor.", bundle: .module)
        case .tooManyBeneficiaries:
            // The section's header shows the limit next to the count, so the message need not.
            String(localized: "An expense cannot have this many beneficiaries.", bundle: .module)
        case .sharesBelowTotalAmount:
            String(localized: "The total of the shares cannot be less than the total amount.", bundle: .module)
        case .sharesExceedTotalAmount:
            String(localized: "The total of the shares cannot be greater than the total amount.", bundle: .module)
        case .nonPositiveShare:
            String(localized: "Every beneficiary must have a share greater than zero.", bundle: .module)
        case .invalidAmount:
            String(
                localized: "Amounts and shares must be greater than zero, with at most 15 digits before the decimal point.",
                bundle: .module
            )
        }
    }
}

/// Why a write, a deletion or a mark of an expense failed, as Android's `ExpenseWriteError` tells
/// that of a one-time expense.
public enum ExpenseWriteError: Error, Hashable, Sendable {
    case noInternet
    case timeout
    case serverUnreachable

    /// The server rejected what was sent.
    case invalidData

    /// The expense had every edit its server allows.
    case conflict

    /// The session is no longer valid.
    case unauthorized

    /// This device does not hold the expense's key yet: re-sealing it under a new one would leave
    /// everyone it is shared with unable to open it, so it waits for the key to arrive.
    case keyMissing

    case unknown

    public var message: String {
        switch self {
        case .noInternet:
            String(localized: "No connection, check your network and try again.", bundle: .module)
        case .timeout:
            String(localized: "The server took too long to answer, please try again.", bundle: .module)
        case .serverUnreachable:
            String(localized: "Server is unreachable, please try again later.", bundle: .module)
        case .invalidData:
            String(localized: "This expense is invalid.", bundle: .module)
        case .conflict:
            String(localized: "The maximum number of edits has been reached.", bundle: .module)
        case .unauthorized:
            String(localized: "Your session expired, please log in again.", bundle: .module)
        case .keyMissing:
            String(localized: "This expense cannot be edited on this device yet: its key has not arrived.", bundle: .module)
        case .unknown:
            String(localized: "Something went wrong, please try again.", bundle: .module)
        }
    }
}

/// Somebody who deleted their account, by the name this device shows for them, if any.
public struct DeletedBeneficiary: Hashable, Sendable {
    public let userUuid: String
    public let fullName: String?

    public init(userUuid: String, fullName: String?) {
        self.userUuid = userUuid
        self.fullName = fullName
    }
}

/// Why saving the expense form did not go through, as Android's `ExpenseSaveError` tells it.
public enum ExpenseSaveError: Error, Hashable, Sendable {
    /// For a reason told in a sentence of its own.
    case write(ExpenseWriteError)

    /// The server refused the expense for naming people who deleted their account, in the order the
    /// form lists them: every one a new expense names, and only the ones an edit adds. The form
    /// takes them out of its selection for the user to save again.
    case beneficiariesDeleted([DeletedBeneficiary])

    public var message: String {
        switch self {
        case .write(let error):
            return error.message

        case .beneficiariesDeleted(let beneficiaries):
            let names = beneficiaries.compactMap(\.fullName)

            // How many, rather than leaving somebody out of a list of names.
            guard names.count == beneficiaries.count else {
                return beneficiaries.count == 1
                    ? String(localized: "A beneficiary deleted their account.", bundle: .module)
                    : String(localized: "\(beneficiaries.count) beneficiaries deleted their accounts.", bundle: .module)
            }

            let listed = names.formatted(.list(type: .and))

            return names.count == 1
                ? String(localized: "\(listed) deleted their account.", bundle: .module)
                : String(localized: "\(listed) deleted their accounts.", bundle: .module)
        }
    }
}

/// Why the history of an expense could not be read.
public enum ExpenseHistoryError: Error, Hashable, Sendable {
    case noInternet
    case timeout
    case serverUnreachable

    /// Anything else: nothing on this device can open it, the server refused, …
    case unknown

    public var message: String {
        switch self {
        case .noInternet: ExpenseWriteError.noInternet.message
        case .timeout: ExpenseWriteError.timeout.message
        case .serverUnreachable: ExpenseWriteError.serverUnreachable.message
        case .unknown: String(localized: "An error occurred.", bundle: .module)
        }
    }
}
