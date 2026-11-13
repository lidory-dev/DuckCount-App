import Foundation

/// What one person comes to in a group, as its Balance tab shows it, as Android's
/// `GroupMemberWithBalance` holds it.
struct BalanceRow: Identifiable, Hashable, Sendable {
    /// Their UUID.
    let id: String

    /// What they come to, written as the library writes it, "-12.50".
    let amount: String

    /// `amount` as a number, positive when they are owed, negative when they owe.
    let value: Decimal

    /// Whether they are no longer in the group, the expenses still naming them.
    let isFormerMember: Bool

    /// How long their bar is against the longest, which is 1: what they come to against what the
    /// person furthest from even comes to.
    let fraction: Double

    /// The rows the Balance tab shows of `balances`, as Android's `GroupBalanceScreen` lists them:
    /// everybody who comes to anything but zero, members and former members alike, by what they
    /// come to, the most owed first. Ties keep an order of their own, by UUID, a dictionary having
    /// none.
    static func rows(of balances: GroupBalances) -> [BalanceRow] {
        let values = balances.netAmounts.compactMap { userUuid, amount -> (String, String, Decimal)? in
            guard let value = Decimal(string: amount, locale: Locale(identifier: "en_US_POSIX")), value != 0 else {
                return nil
            }

            return (userUuid, amount, value)
        }

        let longest = values.map { abs($0.2) }.max() ?? 0

        return values
            .sorted { first, second in
                first.2 != second.2 ? first.2 > second.2 : first.0 < second.0
            }
            .map { userUuid, amount, value in
                BalanceRow(
                    id: userUuid,
                    amount: amount,
                    value: value,
                    isFormerMember: !balances.memberUuids.contains(userUuid),
                    fraction: longest == 0 ? 0 : NSDecimalNumber(decimal: abs(value) / longest).doubleValue
                )
            }
    }
}

/// A group's Balance and Settle up tabs' state, as Android's `GroupBalanceViewModel` and
/// `GroupSettleUpViewModel` hold it: who owes whom, worked out again as the members or the expenses
/// change, and named as names are read.
@MainActor
final class GroupBalancesViewModel: ObservableObject {
    enum State: Equatable {
        case loading

        /// The library could not work the balances out.
        case unavailable

        case content(GroupBalances)
    }

    @Published private(set) var state = State.loading

    /// The names of everybody the balances name, as they are read.
    @Published private(set) var names = MemberNames()

    /// The debt whose settlement is on its way to the server: another tap meanwhile would record it
    /// twice, each one an expense of its own, the debt showing until the first is taken.
    @Published private(set) var settlingDebtId: String?

    /// Why the last settlement was not recorded, until it is told: the debt is still listed, for the
    /// user to try again.
    @Published var settlementError: GroupFailure?

    let groupId: String
    let selfUuid: String?

    private let callBacks: any GroupCallBacks
    private let balancesTask = TaskHandle()
    private var nameFollower: NameFollower?

    /// The balances as last worked out, which show once the names of everybody they name are read,
    /// as Android's tabs wait on them: shown before, a debt would read " owes you 20.00 €". Until
    /// then, the tabs show what they showed, loading at first.
    private var readBalances: GroupBalances?

    init(groupId: String, callBacks: any GroupCallBacks) {
        self.groupId = groupId
        self.callBacks = callBacks
        self.selfUuid = callBacks.authenticatedUserUuid()

        nameFollower = NameFollower { [weak self] names in
            self?.names = names
            self?.showReadBalances()
        }

        follow(callBacks.balances(groupId: groupId), owner: self, into: balancesTask) { owner, balances in
            owner.readBalances = balances

            guard let balances else {
                owner.state = .unavailable
                return
            }

            owner.nameFollower?.follow(GroupBalancesViewModel.namedPeople(of: balances), callBacks: callBacks)
            owner.showReadBalances()
        }
    }

    /// Everybody the tabs name in `balances`: whoever comes to anything, and the debtor and creditor
    /// of each debt. Nobody for a group all settled up, which shows at once.
    static func namedPeople(of balances: GroupBalances) -> Set<String> {
        Set(BalanceRow.rows(of: balances).map(\.id) + balances.debts.flatMap { [$0.debtorUuid, $0.creditorUuid] })
    }

    /// Shows the balances as last worked out, once the names of everybody they name are read.
    private func showReadBalances() {
        guard
            let readBalances,
            state != .content(readBalances),
            GroupBalancesViewModel.namedPeople(of: readBalances).isSubset(of: names.read)
        else {
            return
        }

        state = .content(readBalances)
    }

    /// The rows the Balance tab shows, see `BalanceRow.rows(of:)`.
    var balanceRows: [BalanceRow] {
        guard case .content(let balances) = state else {
            return []
        }

        return BalanceRow.rows(of: balances)
    }

    /// Every repayment that would settle the group, the user's and everybody else's.
    var debts: [Debt] {
        guard case .content(let balances) = state else {
            return []
        }

        return balances.debts
    }

    /// The group's currency symbol, which every amount is in.
    var currencySymbol: String {
        guard case .content(let balances) = state else {
            return ""
        }

        return balances.currencySymbol
    }

    /// What the Balance tab calls the person of `row`: their name, and that they are a former member
    /// when they are.
    func name(of row: BalanceRow) -> String {
        let name = names.displayName(of: row.id)

        return row.isFormerMember ? String(localized: "\(name) (former member)", bundle: .module) : name
    }

    /// `amount` as the tabs write it, next to the group's currency symbol, as Android writes it.
    func amountLabel(_ amount: String) -> String {
        "\(amount) \(currencySymbol)"
    }

    /// What the Settle up tab says of `debt`, worded for the user reading it, see `DebtSide`.
    func sentence(for debt: Debt) -> String {
        let amount = amountLabel(debt.amount)
        let debtor = names.displayName(of: debt.debtorUuid)
        let creditor = names.displayName(of: debt.creditorUuid)

        return switch debt.side(for: selfUuid) {
        case .owedToUser:
            String(localized: "\(debtor) owes you \(amount)", bundle: .module)
        case .owedByUser:
            String(localized: "You owe \(creditor) \(amount)", bundle: .module)
        case .betweenOthers:
            String(localized: "\(debtor) owes \(creditor) \(amount)", bundle: .module)
        }
    }

    /// Records that the debtor of `debt` paid the user back, at once, with no confirmation, as
    /// Android does: only the creditor can, and only one at a time.
    func settle(_ debt: Debt) {
        guard settlingDebtId == nil, debt.side(for: selfUuid) == .owedToUser else {
            return
        }

        settlingDebtId = debt.id

        Task {
            let failure = await callBacks.recordSettlement(groupId: groupId, debtorUuid: debt.debtorUuid, amount: debt.amount)

            settlingDebtId = nil

            // Recorded, the debt leaves the list once the expense lands.
            if let failure {
                settlementError = failure
            }
        }
    }
}
