import Foundation

/// The list of a group's expenses' state, as Android's `ExpenseListViewModel` holds it for a group:
/// every expense of it, those deleted included, which stay listed struck through.
@MainActor
final class GroupExpenseListViewModel: ObservableObject {
    @Published private(set) var days: [ExpenseDay] = []

    /// The names of the expenses' creditors, and of who each settlement paid back, as they are read.
    @Published private(set) var names = NameBook()

    /// Whether the device's copy was read, which an empty list says nothing about until it is.
    @Published private(set) var hasReadExpenses = false

    /// Whether the user is no longer a member of the group, which leaves it read-only: nil until it
    /// is read. Followed with the list open, since a sync can read a removal then.
    @Published private(set) var isGroupLeft: Bool?

    let groupId: String

    /// The user, whose share each row shows.
    let selfUuid: String?

    private let callBacks: any ExpenseCallBacks
    private var pager: ExpensePager?
    private let groupLeftTask = TaskHandle()
    private var nameFollower: NameFollower?

    init(groupId: String, callBacks: any ExpenseCallBacks) {
        self.groupId = groupId
        self.callBacks = callBacks
        self.selfUuid = callBacks.authenticatedUserUuid()

        nameFollower = NameFollower { [weak self] names in
            self?.names = names
        }

        // A page at a time, as Android's list reads them.
        pager = ExpensePager(read: { callBacks.groupExpenses(groupId: groupId, limit: $0) }) { [weak self] expenses in
            guard let self else {
                return
            }

            self.days = ExpenseDay.grouping(expenses)
            self.hasReadExpenses = true
            self.nameFollower?.follow(
                Set(expenses.flatMap { [$0.creditorUuid, $0.settlementRecipientUuid].compactMap { $0 } }),
                callBacks: self.callBacks
            )
        }

        let isLeft = callBacks.isGroupLeft(groupId: groupId)

        groupLeftTask.replace(
            with: Task { [weak self] in
                for await isLeft in isLeft {
                    guard let self else {
                        return
                    }

                    self.isGroupLeft = isLeft
                }
            }
        )
    }

    /// Whether the list is still being read, rather than empty.
    var isLoading: Bool {
        days.isEmpty && !hasReadExpenses
    }

    /// Reads the next page once the row of `expenseId` nears the end of the list, see `ExpensePager`.
    func rowAppeared(_ expenseId: String) {
        pager?.rowAppeared(expenseId)
    }

    /// Whether an expense can be added: not to a group the user is no longer in, nor before that is
    /// known.
    var canAddExpense: Bool {
        isGroupLeft == false
    }

    /// Brings the group and its expenses up to date with the server, which the list shows as they
    /// are stored.
    func refresh() async {
        await callBacks.refreshGroup(groupId: groupId)
    }
}
