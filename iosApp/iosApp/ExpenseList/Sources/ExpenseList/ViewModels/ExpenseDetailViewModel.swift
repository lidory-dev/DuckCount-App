import Foundation

/// What the user may do with an expense on its screen, as Android's `ExpenseActions` says it, see
/// `init(expense:selfUuid:isGroupLeft:maxGroupExpenseEdits:)`.
struct ExpenseActions: Equatable {
    let canEdit: Bool

    /// Whether the expense of a group had every edit it takes, which is then why it is not edited,
    /// and which the screen says: it stands at its last revision, one more than the edits it takes.
    /// Deleting and restoring it are no edit, so it is still deleted and restored. Never in a group
    /// the user is no longer in, where nothing is edited anyway, nor for a settlement, which nobody
    /// edits, and which never gets there.
    let isEditLimitReached: Bool

    let canDelete: Bool

    /// Whether the expense, once deleted, can be restored: not in a group the user is no longer in,
    /// and never a settlement.
    let canRestore: Bool

    /// Whether it can be marked as paid back: a one-time expense alone, by its creditor.
    let canSettle: Bool

    /// Whether its versions can be shown: never a settlement's, which nobody edits, nor in a group
    /// the user is no longer a member of, whose server serves them to its current members alone.
    let canShowHistory: Bool
}

extension ExpenseActions {
    /// What the user, `selfUuid`, may do with `expense`: nothing that changes it in a group they are
    /// no longer a member of, removed or left, `isGroupLeft`, which stays read-only, its expenses
    /// with it.
    ///
    /// A one-time expense is edited, deleted and marked as paid back by its creditor alone, who signs
    /// it, the server taking it from nobody else. Any member edits and deletes an expense of their
    /// group, which takes `maxGroupExpenseEdits` edits, and none once it stands at its last revision,
    /// see `isEditLimitReached`: every member's device refuses a version past it, and the server
    /// stores none.
    ///
    /// A settlement is recorded by its recipient alone, its one beneficiary, and only they delete it.
    /// Nobody edits one, nor restores it once deleted: it stays in the list struck through, as its
    /// confirmation says. Every member's device refuses a version of one, or a deletion, that does
    /// otherwise.
    init(expense: Expense, selfUuid: String?, isGroupLeft: Bool, maxGroupExpenseEdits: Int) {
        let isGroupExpense = expense.isGroupExpense

        // Created at revision 1, it stands at its last once it had every edit it takes. A one-time
        // expense's revision counts every edit tried, so it says nothing of the kind.
        let isEditLimitReached = !isGroupLeft && isGroupExpense
            && (expense.revision.map { $0 >= 1 + Int64(maxGroupExpenseEdits) } ?? false)

        let canDelete: Bool

        if expense.isSettlement {
            canDelete = expense.beneficiaries.contains { $0.userUuid == selfUuid }
        } else if isGroupExpense {
            canDelete = true
        } else {
            canDelete = expense.creditorUuid == selfUuid
        }

        self.init(
            canEdit: !isGroupLeft && !expense.isSettlement && !isEditLimitReached
                && (isGroupExpense || expense.creditorUuid == selfUuid),
            isEditLimitReached: isEditLimitReached,
            canDelete: !isGroupLeft && canDelete,
            canRestore: !isGroupLeft && !expense.isSettlement,
            canSettle: !isGroupExpense && expense.creditorUuid == selfUuid,
            canShowHistory: !isGroupLeft && !expense.isSettlement
        )
    }
}

/// An expense's screen's state, as Android's `ExpenseDetailViewModel` holds it.
@MainActor
public final class ExpenseDetailViewModel: ObservableObject {
    /// The expense as the device shows it, nil until it is read. Kept once it is gone, deleted
    /// from this screen or elsewhere, for the screen to close on rather than to blank out.
    @Published private(set) var expense: Expense?

    /// The names of its creditor, its beneficiaries and who deleted it, as they are read.
    @Published private(set) var names = NameBook()

    /// Whether the user is no longer a member of the expense's group, which leaves it read-only: nil
    /// until it is read, and false for a one-time expense. Followed with the screen open, since a
    /// sync can read a removal then.
    @Published private(set) var isGroupLeft: Bool?

    /// True while marking it as paid back, or no longer, which nothing else is done during.
    @Published private(set) var isSettling = false

    /// True while deleting it.
    @Published private(set) var isDeleting = false

    /// True while restoring it.
    @Published private(set) var isRestoring = false

    /// True once it was deleted or restored, which leaves the screen nothing to show.
    @Published private(set) var isClosed = false

    /// Why the last deletion, restoration or mark failed, until it is told.
    @Published var error: ExpenseWriteError?

    let expenseId: String
    let selfUuid: String?

    /// The most edits an expense of a group takes, which the screen names once it had them all.
    let maxGroupExpenseEdits: Int

    private let callBacks: any ExpenseCallBacks
    private let expenseTask = TaskHandle()
    private let groupLeftTask = TaskHandle()
    private var followedGroupId: String?
    private var nameFollower: NameFollower?

    public init(expenseId: String, callBacks: any ExpenseCallBacks) {
        self.expenseId = expenseId
        self.callBacks = callBacks
        self.selfUuid = callBacks.authenticatedUserUuid()
        self.maxGroupExpenseEdits = callBacks.maxGroupExpenseEdits

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

                    self.show(expense)
                }
            }
        )
    }

    /// What the user may do with the expense, nil until the expense, and whether its group is left,
    /// are read: offered before then, an edit could be one the group no longer takes.
    var actions: ExpenseActions? {
        guard let expense else {
            return nil
        }

        let isLeft: Bool

        if expense.isGroupExpense {
            guard let isGroupLeft else {
                return nil
            }

            isLeft = isGroupLeft
        } else {
            isLeft = false
        }

        return ExpenseActions(
            expense: expense,
            selfUuid: selfUuid,
            isGroupLeft: isLeft,
            maxGroupExpenseEdits: maxGroupExpenseEdits
        )
    }

    /// Whether a write is under way, which nothing else is done during: deleting the expense while
    /// its mark is written would race the mark to the server.
    var isBusy: Bool {
        isSettling || isDeleting || isRestoring || isClosed
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

    /// Restores the deleted group expense, which closes the screen once the server took it, as a
    /// deletion does.
    func restore() {
        guard !isBusy else {
            return
        }

        isRestoring = true

        Task {
            let failure = await callBacks.restore(expenseId: expenseId)

            isRestoring = false

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

    private func show(_ expense: Expense) {
        self.expense = expense

        nameFollower?.follow(
            Set([expense.creditorUuid] + expense.beneficiaries.map(\.userUuid) + [expense.deletedByUuid].compactMap { $0 }),
            callBacks: callBacks
        )

        guard let groupId = expense.groupId else {
            isGroupLeft = false
            return
        }

        followGroupLeft(groupId: groupId)
    }

    /// Follows whether the user is still in the group `groupId`, once: the expense is shown again
    /// with each change, its group never with it.
    private func followGroupLeft(groupId: String) {
        guard groupId != followedGroupId else {
            return
        }

        followedGroupId = groupId

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
}
