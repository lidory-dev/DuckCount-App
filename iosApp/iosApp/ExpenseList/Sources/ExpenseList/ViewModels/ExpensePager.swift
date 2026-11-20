import Foundation

/// Reads a list's expenses a page at a time, newest first, as Android's `Pager` reads them from the
/// database: the newest `pageSize`, then `pageSize` more each time the list nears the end of what it
/// read, everything read so far followed as it changes. Each page is a larger read in place of the
/// last, the shared module's feeds being of the newest expenses, see `ExpenseCallBacks.expenses(limit:)`,
/// so that a long list is never read whole, nor turned into Swift whole at every change.
@MainActor
final class ExpensePager {
    /// Android's, `ExpenseRepository.PAGE_SIZE`.
    static let pageSize = 20

    /// How near the end of what was read a row has to be to read the next page.
    static let prefetchDistance = 5

    /// How many expenses are read.
    private(set) var limit = ExpensePager.pageSize

    private let read: @MainActor (Int) -> AsyncStream<[Expense]>
    private let onRead: @MainActor ([Expense]) -> Void
    private let task = TaskHandle()

    /// The last expenses read, whose rows read the next page as they appear.
    private var lastIds: Set<String> = []

    /// Whether as many expenses came as were asked for, so that there may be more.
    private var mayHaveMore = false

    /// Reads with `read`, given how many to read, and hands what it reads to `onRead`, each time it
    /// changes.
    init(read: @escaping @MainActor (Int) -> AsyncStream<[Expense]>, onRead: @escaping @MainActor ([Expense]) -> Void) {
        self.read = read
        self.onRead = onRead
        follow()
    }

    /// Reads the next page if the row of `expenseId` is near the end of what was read, and there may
    /// be more.
    func rowAppeared(_ expenseId: String) {
        guard mayHaveMore, lastIds.contains(expenseId) else {
            return
        }

        // Once, until the larger read comes back.
        mayHaveMore = false
        limit += Self.pageSize
        follow()
    }

    private func follow() {
        let expenses = read(limit)
        let limit = limit

        task.replace(
            with: Task { [weak self] in
                for await expenses in expenses {
                    guard let self else {
                        return
                    }

                    self.mayHaveMore = expenses.count >= limit
                    self.lastIds = Set(expenses.suffix(Self.prefetchDistance).map(\.id))
                    self.onRead(expenses)
                }
            }
        )
    }
}
