public enum ExpenseValidationError: Hashable {
    case sharesBelowTotalAmount
    case sharesExceedTotalAmount
    case nonPositiveShare
    case sharesEmpty
    /// More beneficiaries than `limit`, the most an expense may list.
    case tooManyBeneficiaries(limit: Int)
    case otherKnownError

    public var message: String {
        switch self {
        case .sharesBelowTotalAmount: "The sum of shares is below the total amount."
        case .sharesExceedTotalAmount: "The sum of shares exceeds the total amount."
        case .nonPositiveShare: "Each share must be greater than zero."
        case .sharesEmpty: "There must be at least one beneficiary, and each beneficiary must have a share."
        case .tooManyBeneficiaries(let limit): "An expense can have at most \(limit) beneficiaries."
        case .otherKnownError: "The expense is invalid."
        }
    }
}
