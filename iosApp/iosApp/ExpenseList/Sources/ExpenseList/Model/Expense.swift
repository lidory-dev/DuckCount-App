import Foundation

/// A currency an expense is written in, as Android's `Currency` holds it.
public struct Currency: Hashable, Sendable {
    /// Identifies this record of the currency, kept as it was read.
    public let id: String

    /// Its ISO 4217 code, which every device names it by and which its localized name and the way
    /// its amounts are written are read from.
    public let code: String

    public let symbol: String
    public let hasSubunits: Bool

    public init(id: String, code: String, symbol: String, hasSubunits: Bool) {
        self.id = id
        self.code = code
        self.symbol = symbol
        self.hasSubunits = hasSubunits
    }

    /// Whether `other` is the same currency, whatever record of it each one is: what an edit has
    /// to change for the currency to count as changed.
    func isSameCurrency(as other: Currency) -> Bool {
        code == other.code && symbol == other.symbol && hasSubunits == other.hasSubunits
    }
}

/// Somebody an expense can name, as the people pickers list them.
public struct User: Identifiable, Hashable, Sendable {
    public let id: String
    public let fullName: String

    public init(id: String, fullName: String) {
        self.id = id
        self.fullName = fullName
    }
}

/// Somebody an expense is shared with, and their share of it, as Android's `Beneficiary` holds it.
public struct Beneficiary: Hashable, Sendable {
    public var id: String
    public var userUuid: String

    /// What they owe, written as the library writes an amount, see `toAmount`.
    public var share: String

    /// Whether the share was set by hand rather than split from what the others' leave.
    public var isShareFixed: Bool

    /// The percentage of the amount the share was set as, nil for one set otherwise. `isShareFixed`
    /// holds for it too, a percentage being set by hand.
    public var sharePercentage: String?

    /// How many parts of what the fixed shares and percentages leave the share was set as, nil for
    /// one set otherwise. `isShareFixed` tells who it was set by hand for from who counts for `1`
    /// because somebody else's was, whom the library states one for too.
    public var shareWeight: String?

    public init(
        id: String = UUID().uuidString.lowercased(),
        userUuid: String,
        share: String,
        isShareFixed: Bool = false,
        sharePercentage: String? = nil,
        shareWeight: String? = nil
    ) {
        self.id = id
        self.userUuid = userUuid
        self.share = share
        self.isShareFixed = isShareFixed
        self.sharePercentage = sharePercentage
        self.shareWeight = shareWeight
    }

    /// Whether this share was named as an amount by hand, and not set as a percentage or a weight.
    var isFixedAmount: Bool {
        isShareFixed && sharePercentage == nil && shareWeight == nil
    }
}

/// An expense, one-time or in a group, as Android's `ExpenseWithBeneficiaries` holds one.
public struct Expense: Identifiable, Hashable, Sendable {
    public let id: String

    /// When it happened, as an ISO 8601 date and time with no time zone, as every device writes it.
    public let timestamp: String

    public let title: String
    public let description: String
    public let amount: String
    public let creditorUuid: String
    public let currency: Currency

    /// In the order they were picked, which decides who the library gives the cents that do not
    /// divide evenly to.
    public let beneficiaries: [Beneficiary]

    /// Whether its creditor marked it as paid back.
    public let isSettled: Bool

    /// When they did, as `timestamp` is written, in the device's time zone; nil when it is not settled.
    public let settledAt: String?

    /// The revision of the version shown.
    public let revision: Int64?

    /// The group it belongs to, or nil for a one-time expense.
    public let groupId: String?

    /// Whether it records a repayment between two members of its group, rather than spending: its
    /// creditor is who paid, its single beneficiary who was paid back. Never edited nor restored.
    public let isSettlement: Bool

    /// Whether a member of its group deleted it, which any member can undo, but for a settlement.
    /// Still listed, out of the balances.
    public let isDeleted: Bool

    /// Who deleted it, while it is deleted.
    public let deletedByUuid: String?

    /// Whether it belongs to a group.
    public var isGroupExpense: Bool {
        groupId != nil
    }

    public init(
        id: String,
        timestamp: String,
        title: String,
        description: String,
        amount: String,
        creditorUuid: String,
        currency: Currency,
        beneficiaries: [Beneficiary],
        isSettled: Bool = false,
        settledAt: String? = nil,
        revision: Int64? = nil,
        groupId: String? = nil,
        isSettlement: Bool = false,
        isDeleted: Bool = false,
        deletedByUuid: String? = nil
    ) {
        self.id = id
        self.timestamp = timestamp
        self.title = title
        self.description = description
        self.amount = amount
        self.creditorUuid = creditorUuid
        self.currency = currency
        self.beneficiaries = beneficiaries
        self.isSettled = isSettled
        self.settledAt = settledAt
        self.revision = revision
        self.groupId = groupId
        self.isSettlement = isSettlement
        self.isDeleted = isDeleted
        self.deletedByUuid = deletedByUuid
    }
}

/// One version of an expense, as its history shows it.
public struct ExpenseVersion: Identifiable, Hashable, Sendable {
    /// Which version this is, which no other version of the expense shares.
    public let revision: Int64

    public let expense: Expense

    /// Who made it.
    public let authorUuid: String

    /// Whether this is the expense as it was created, rather than as an edit left it.
    public let isCreation: Bool

    /// When it was made, written as `Expense.timestamp` is, or nil when that is not known.
    public let madeAt: String?

    public var id: Int64 { revision }

    public init(revision: Int64, expense: Expense, authorUuid: String, isCreation: Bool, madeAt: String?) {
        self.revision = revision
        self.expense = expense
        self.authorUuid = authorUuid
        self.isCreation = isCreation
        self.madeAt = madeAt
    }
}

/// What reading the history of an expense gave.
public enum ExpenseHistory: Sendable {
    /// Its versions, the one shown first and the earliest last.
    case versions([ExpenseVersion])

    /// Why there are none to show.
    case unavailable(ExpenseHistoryError)
}
