import Foundation

/// What the user may do with a one-time expense on its screen, as Android's `ExpenseActions` says
/// it of one: only its creditor edits it, deletes it and marks it as paid back, which they sign,
/// the server taking it from nobody else; anybody on it reads its history.
struct ExpenseActions: Equatable {
    let canEdit: Bool
    let canDelete: Bool
    let canSettle: Bool
    let canShowHistory: Bool

    init(expense: Expense, selfUuid: String?) {
        let isCreditor = expense.creditorUuid == selfUuid

        canEdit = isCreditor
        canDelete = isCreditor
        canSettle = isCreditor
        canShowHistory = true
    }
}

/// An expense's screen's state, as Android's `ExpenseDetailViewModel` holds it.
@MainActor
public final class ExpenseDetailViewModel: ObservableObject {
    /// The expense as the device shows it, nil until it is read. Kept once it is gone, deleted
    /// from this screen or elsewhere, for the screen to close on rather than to blank out.
    @Published private(set) var expense: Expense?

    /// The names of its creditor and beneficiaries, as they are read.
    @Published private(set) var names = NameBook()

    /// True while marking it as paid back, or no longer, which nothing else is done during.
    @Published private(set) var isSettling = false

    /// True while deleting it.
    @Published private(set) var isDeleting = false

    /// True once it was deleted, which leaves the screen nothing to show.
    @Published private(set) var isClosed = false

    /// Why the last deletion or mark failed, until it is told.
    @Published var error: ExpenseWriteError?

    let expenseId: String
    let selfUuid: String?

    private let callBacks: any ExpenseCallBacks
    private let expenseTask = TaskHandle()
    private var nameFollower: NameFollower?

    public init(expenseId: String, callBacks: any ExpenseCallBacks) {
        self.expenseId = expenseId
        self.callBacks = callBacks
        self.selfUuid = callBacks.authenticatedUserUuid()

        nameFollower = NameFollower { [weak self] names in
            self?.names = names
        }

        let expense = callBacks.expense(id: expenseId)

        expenseTask.replace(
            with: Task { [weak self] in
                for await expense in expense {
                    guard let self else {
                        return
                    }

                    guard let expense else {
                        continue
                    }

                    self.expense = expense
                    self.nameFollower?.follow(
                        Set([expense.creditorUuid] + expense.beneficiaries.map(\.userUuid)),
                        callBacks: self.callBacks
                    )
                }
            }
        )
    }

    var actions: ExpenseActions? {
        expense.map { ExpenseActions(expense: $0, selfUuid: selfUuid) }
    }

    /// Whether a write is under way, which nothing else is done during: deleting the expense while
    /// its mark is written would race the mark to the server.
    var isBusy: Bool {
        isSettling || isDeleting || isClosed
    }

    /// Its beneficiaries as the screen lists them: the user first, then the others by name.
    var beneficiaries: [Beneficiary] {
        (expense?.beneficiaries ?? []).sortedByName(
            selfUuid: selfUuid,
            uuidOf: \.userUuid,
            nameOf: { self.names.name(of: $0.userUuid) }
        )
    }

    func delete() {
        guard !isBusy else {
            return
        }

        isDeleting = true

        Task {
            let failure = await callBacks.delete(expenseId: expenseId)

            isDeleting = false

            if let failure {
                error = failure
            } else {
                isClosed = true
            }
        }
    }

    /// Marks the expense as paid back, or no longer, as it is shown now.
    func toggleSettled() {
        guard !isBusy, let expense else {
            return
        }

        isSettling = true

        Task {
            let failure = await callBacks.setSettled(expenseId: expenseId, isSettled: !expense.isSettled)

            isSettling = false
            error = failure
        }
    }
}
