import SwiftUI

/// What the user is to an expense, which says what its row shows of it, as Android's `ExpenseStatus`.
enum ExpenseRole: Equatable {
    /// They paid it: the others owe them their shares.
    case creditor

    /// Somebody else paid it, and they owe their share.
    case beneficiary

    /// They are on it no more, or never were.
    case neutral

    /// Shown for a share the user is owed.
    static let owedColor = Color(red: 0x5C / 255.0, green: 0xCB / 255.0, blue: 0x5F / 255.0)

    /// Shown for a share the user owes.
    static let owingColor = Color(red: 0xE6 / 255.0, green: 0x39 / 255.0, blue: 0x46 / 255.0)

    var color: Color {
        switch self {
        case .creditor: Self.owedColor
        case .beneficiary: Self.owingColor
        case .neutral: .secondary
        }
    }
}

extension Expense {
    /// What the user, `selfUuid`, is to it.
    func role(selfUuid: String?) -> ExpenseRole {
        if creditorUuid == selfUuid {
            return .creditor
        }

        return beneficiaries.contains { $0.userUuid == selfUuid } ? .beneficiary : .neutral
    }

    /// What the user, `selfUuid`, is owed of it, as its creditor, or owes, as one of its
    /// beneficiaries, the latter below zero; nil when neither.
    func personalBalance(selfUuid: String?) -> Decimal? {
        switch role(selfUuid: selfUuid) {
        case .creditor:
            return beneficiaries
                .filter { $0.userUuid != selfUuid }
                .reduce(Decimal.zero) { total, beneficiary in total + beneficiary.share.decimalValue }

        case .beneficiary:
            return beneficiaries.first { $0.userUuid == selfUuid }.map { -$0.share.decimalValue }

        case .neutral:
            return nil
        }
    }

    /// What its row shows the user is owed of it or owes, see `personalBalance(selfUuid:)`: nothing
    /// for a settlement, which is no spending, its amount being all there is to tell of it.
    func shownBalance(selfUuid: String?) -> Decimal? {
        isSettlement ? nil : personalBalance(selfUuid: selfUuid)
    }

    /// Who a settlement paid back, its one beneficiary, or nil for any other expense.
    var settlementRecipientUuid: String? {
        isSettlement ? beneficiaries.first?.userUuid : nil
    }
}

extension String {
    /// The amount `self` says, as the library writes one, or zero when it says none.
    var decimalValue: Decimal {
        Decimal(string: self, locale: .posix) ?? .zero
    }
}
