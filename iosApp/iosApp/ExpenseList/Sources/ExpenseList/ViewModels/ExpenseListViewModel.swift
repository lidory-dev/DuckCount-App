import Foundation

/// The expenses of one day, as the list shows them under its date.
struct ExpenseDay: Identifiable, Equatable {
    /// The day, "2026-11-10", as the expenses' timestamps write it.
    let id: String

    /// Its date, or nil when the timestamps say none.
    let date: Date?

    let expenses: [Expense]

    /// `expenses`, newest first, by the day they happened on, as the device writes it with no
    /// time zone: together, since they come in that order.
    static func grouping(_ expenses: [Expense]) -> [ExpenseDay] {
        var days: [ExpenseDay] = []
        var current: [Expense] = []

        func close() {
            guard let first = current.first else {
                return
            }

            let day = LocalDateTime.day(of: first.timestamp)

            days.append(ExpenseDay(id: day, date: LocalDateTime.date(from: day + "T12:00"), expenses: current))
            current = []
        }

        for expense in expenses {
            if let first = current.first, LocalDateTime.day(of: first.timestamp) != LocalDateTime.day(of: expense.timestamp) {
                close()
            }

            current.append(expense)
        }

        close()

        return days
    }
}

/// The list of one-time expenses' state, as Android's `ExpenseListViewModel` holds it.
@MainActor
public final class ExpenseListViewModel: ObservableObject {
    @Published private(set) var days: [ExpenseDay] = []

    /// The names of the expenses' creditors, as they are read.
    @Published private(set) var names = NameBook()

    /// Whether the device's copy was read, which an empty list says nothing about until it is.
    @Published private(set) var hasReadExpenses = false

    /// Whether the first refresh is under way, which an empty list says nothing about either: that
    /// is somebody who just logged in, and their expenses are on the way.
    @Published private(set) var isFirstRefreshing = false

    /// The user, whose share each row shows.
    let selfUuid: String?

    private let callBacks: any ExpenseCallBacks
    private var pager: ExpensePager?
    private var nameFollower: NameFollower?
    private var hasRefreshed = false

    public init(callBacks: any ExpenseCallBacks) {
        self.callBacks = callBacks
        self.selfUuid = callBacks.authenticatedUserUuid()

        nameFollower = NameFollower { [weak self] names in
            self?.names = names
        }

        // A page at a time, as Android's list reads them.
        pager = ExpensePager(read: { callBacks.expenses(limit: $0) }) { [weak self] expenses in
            guard let self else {
                return
            }

            self.days = ExpenseDay.grouping(expenses)
            self.hasReadExpenses = true
            self.nameFollower?.follow(Set(expenses.map(\.creditorUuid)), callBacks: self.callBacks)
        }
    }

    /// Reads the next page once the row of `expenseId` nears the end of the list, see `ExpensePager`.
    func rowAppeared(_ expenseId: String) {
        pager?.rowAppeared(expenseId)
    }

    /// Whether the list is still being read, rather than empty.
    var isLoading: Bool {
        days.isEmpty && (!hasReadExpenses || isFirstRefreshing)
    }

    /// Brings the expenses up to date with the server, as the list first opens.
    func refreshOnce() async {
        guard !hasRefreshed else {
            return
        }

        isFirstRefreshing = true
        await refresh()
        isFirstRefreshing = false
    }

    /// Brings the expenses up to date with the server, which the list shows as they are stored.
    func refresh() async {
        hasRefreshed = true

        _ = await callBacks.refresh()
    }
}
