import Foundation
import Testing

@testable import ExpenseList

/// What the user may do with an expense on its screen, as Android's `ExpenseActionsTest` says it:
/// nothing in a group they were removed from, no edit of an expense of a group that had every edit
/// it takes, and with a settlement, only delete it, as the one it was paid to.
struct ExpenseActionsTests {
    /// The edits an expense of a group takes, which its server holds it to as well.
    private let maxEdits = 10

    private func actions(of expense: Expense, selfUuid: String = me, isGroupLeft: Bool = false) -> ExpenseActions {
        ExpenseActions(expense: expense, selfUuid: selfUuid, isGroupLeft: isGroupLeft, maxGroupExpenseEdits: maxEdits)
    }

    /// Nor its history read, which the server serves to current members alone.
    @Test func aGroupExpenseInAGroupTheUserWasRemovedFromCanBeNeitherEditedNorDeletedNorRestored() {
        #expect(
            actions(of: expense(creditorUuid: bob, groupId: trip), isGroupLeft: true)
                == ExpenseActions(
                    canEdit: false,
                    isEditLimitReached: false,
                    canDelete: false,
                    canRestore: false,
                    canSettle: false,
                    canShowHistory: false
                )
        )
    }

    @Test func aGroupExpenseInAGroupTheUserIsStillInCanBeEditedAndDeletedAndRestored() {
        #expect(
            actions(of: expense(creditorUuid: bob, groupId: trip))
                == ExpenseActions(
                    canEdit: true,
                    isEditLimitReached: false,
                    canDelete: true,
                    canRestore: true,
                    canSettle: false,
                    canShowHistory: true
                )
        )
    }

    /// Created at revision 1, edited ten times since: deleting and restoring it are no edit.
    @Test func aGroupExpenseAtItsLastRevisionIsNoLongerEditedButStillDeletedAndRestored() {
        #expect(
            actions(of: expense(creditorUuid: bob, revision: 1 + Int64(maxEdits), groupId: trip))
                == ExpenseActions(
                    canEdit: false,
                    isEditLimitReached: true,
                    canDelete: true,
                    canRestore: true,
                    canSettle: false,
                    canShowHistory: true
                )
        )
    }

    @Test func aGroupExpenseWithOneEditLeftIsStillEdited() {
        #expect(
            actions(of: expense(creditorUuid: bob, revision: Int64(maxEdits), groupId: trip))
                == ExpenseActions(
                    canEdit: true,
                    isEditLimitReached: false,
                    canDelete: true,
                    canRestore: true,
                    canSettle: false,
                    canShowHistory: true
                )
        )
    }

    /// Nothing is edited there anyway: the screen tells of no edit limit.
    @Test func aGroupExpenseAtItsLastRevisionInAGroupTheUserLeftTellsNoEditLimit() {
        #expect(
            actions(of: expense(creditorUuid: bob, revision: 1 + Int64(maxEdits), groupId: trip), isGroupLeft: true)
                == ExpenseActions(
                    canEdit: false,
                    isEditLimitReached: false,
                    canDelete: false,
                    canRestore: false,
                    canSettle: false,
                    canShowHistory: false
                )
        )
    }

    @Test func aOneTimeExpenseIsEditedDeletedAndMarkedAsPaidBackByItsCreditor() {
        #expect(
            actions(of: expense(creditorUuid: me))
                == ExpenseActions(
                    canEdit: true,
                    isEditLimitReached: false,
                    canDelete: true,
                    canRestore: true,
                    canSettle: true,
                    canShowHistory: true
                )
        )
    }

    /// A one-time expense's revision is reserved for every edit tried, those the server refused or
    /// never answered included: it can stand past the last revision of a group's expense with edits
    /// left, which its server counts by those it took.
    @Test func aOneTimeExpensePastTheGroupEditCountIsStillEditedByItsCreditorAndTellsNoEditLimit() {
        #expect(
            actions(of: expense(creditorUuid: me, revision: 1 + Int64(maxEdits)))
                == ExpenseActions(
                    canEdit: true,
                    isEditLimitReached: false,
                    canDelete: true,
                    canRestore: true,
                    canSettle: true,
                    canShowHistory: true
                )
        )
    }

    @Test func aOneTimeExpenseIsNeitherEditedNorDeletedNorMarkedByOneOfItsBeneficiaries() {
        #expect(
            actions(of: expense(creditorUuid: ada))
                == ExpenseActions(
                    canEdit: false,
                    isEditLimitReached: false,
                    canDelete: false,
                    canRestore: true,
                    canSettle: false,
                    canShowHistory: true
                )
        )
    }

    /// Bob paid the user back, which they recorded, its one beneficiary.
    @Test func aSettlementIsDeletedByItsRecipientAndNeitherEditedNorRestored() {
        #expect(
            actions(of: settlement(), selfUuid: me)
                == ExpenseActions(
                    canEdit: false,
                    isEditLimitReached: false,
                    canDelete: true,
                    canRestore: false,
                    canSettle: false,
                    canShowHistory: false
                )
        )
    }

    @Test func aSettlementIsNotDeletedByTheMemberWhoPaidIt() {
        #expect(
            actions(of: settlement(), selfUuid: bob)
                == ExpenseActions(
                    canEdit: false,
                    isEditLimitReached: false,
                    canDelete: false,
                    canRestore: false,
                    canSettle: false,
                    canShowHistory: false
                )
        )
    }

    @Test func aSettlementIsNotDeletedByAnotherMemberOfTheGroup() {
        #expect(
            actions(of: settlement(), selfUuid: ada)
                == ExpenseActions(
                    canEdit: false,
                    isEditLimitReached: false,
                    canDelete: false,
                    canRestore: false,
                    canSettle: false,
                    canShowHistory: false
                )
        )
    }

    @Test func aSettlementInAGroupItsRecipientWasRemovedFromIsNotDeleted() {
        #expect(
            actions(of: settlement(), selfUuid: me, isGroupLeft: true)
                == ExpenseActions(
                    canEdit: false,
                    isEditLimitReached: false,
                    canDelete: false,
                    canRestore: false,
                    canSettle: false,
                    canShowHistory: false
                )
        )
    }
}

/// What a row of the list shows of an expense for the user.
struct ExpenseRowTests {
    @Test func theCreditorIsOwedTheSharesOfEverybodyElse() {
        let lunch = expense(
            amount: "30.00",
            creditorUuid: me,
            beneficiaries: [
                Beneficiary(userUuid: me, share: "10.00"),
                Beneficiary(userUuid: ada, share: "10.00"),
                Beneficiary(userUuid: bob, share: "10.00"),
            ]
        )

        #expect(lunch.role(selfUuid: me) == .creditor)
        #expect(lunch.personalBalance(selfUuid: me) == Decimal(20))
    }

    @Test func aBeneficiaryOwesTheirShare() {
        let lunch = expense(creditorUuid: ada, beneficiaries: [Beneficiary(userUuid: me, share: "7.50")])

        #expect(lunch.role(selfUuid: me) == .beneficiary)
        #expect(lunch.personalBalance(selfUuid: me) == Decimal(string: "-7.5"))
    }

    @Test func somebodyNotOnItIsOwedNothingAndOwesNothing() {
        let lunch = expense(creditorUuid: ada, beneficiaries: [Beneficiary(userUuid: bob, share: "7.50")])

        #expect(lunch.role(selfUuid: me) == .neutral)
        #expect(lunch.personalBalance(selfUuid: me) == nil)
    }

    @Test func expensesAreGroupedByTheDayTheyHappenedOn() {
        let days = ExpenseDay.grouping([
            expense(id: "3", timestamp: "2026-11-10T20:00:00"),
            expense(id: "2", timestamp: "2026-11-10T08:00:00.5"),
            expense(id: "1", timestamp: "2026-11-09T23:59"),
        ])

        #expect(days.map(\.id) == ["2026-11-10", "2026-11-09"])
        #expect(days.map { $0.expenses.map(\.id) } == [["3", "2"], ["1"]])
        #expect(days.allSatisfy { $0.date != nil })
    }

    @Test func everyListOfPeopleNamesTheUserFirstThenTheOthersByNameAndTheNamelessLast() {
        let people = [("z", "Zoé"), ("nameless", nil), ("e", "Émile"), (me, "Zed"), ("a", "Ada")]

        let sorted = people.sortedByName(selfUuid: me, uuidOf: \.0, nameOf: \.1)

        #expect(sorted.map(\.0) == [me, "a", "e", "z", "nameless"])
    }
}

@MainActor
struct ExpenseListViewModelTests {
    @Test func anEmptyListIsLoadingUntilItIsReadAndRefreshed() async {
        let callBacks = FakeExpenseCallBacks()
        let viewModel = ExpenseListViewModel(callBacks: callBacks)

        #expect(viewModel.isLoading)

        callBacks.expenseList.send([])

        await settle { viewModel.hasReadExpenses }

        #expect(!viewModel.isLoading)

        await viewModel.refreshOnce()
        await viewModel.refreshOnce()

        #expect(callBacks.refreshCount == 1)
    }

    @Test func theCreditorsOfTheExpensesAreNamed() async {
        let callBacks = FakeExpenseCallBacks()
        let viewModel = ExpenseListViewModel(callBacks: callBacks)

        callBacks.expenseList.send([expense(creditorUuid: ada), expense(id: "other", creditorUuid: "stranger")])

        await settle { viewModel.names.name(of: ada) != nil }

        #expect(viewModel.names.displayName(of: ada) == "Ada")
        #expect(viewModel.names.displayName(of: "stranger") == NameBook.unknownPerson)
    }

    /// A page at a time, as Android's list reads them: the next once a row near the end appears.
    @Test func theExpensesAreReadAPageAtATime() async {
        let callBacks = FakeExpenseCallBacks()
        let viewModel = ExpenseListViewModel(callBacks: callBacks)
        let expenses = numberedExpenses(45)

        callBacks.expenseList.send(expenses)

        await settle { viewModel.shownIds.count == 20 }

        #expect(callBacks.expenseList.limits == [20])

        // Far from the end: nothing more.
        viewModel.rowAppeared(expenses[3].id)
        #expect(callBacks.expenseList.limits == [20])

        viewModel.rowAppeared(expenses[17].id)
        await settle { viewModel.shownIds.count == 40 }

        #expect(callBacks.expenseList.limits == [20, 40])

        viewModel.rowAppeared(expenses[39].id)
        await settle { viewModel.shownIds.count == 45 }

        #expect(viewModel.shownIds == expenses.map(\.id))

        // Fewer came than were asked for: there are no more.
        viewModel.rowAppeared(expenses[44].id)
        #expect(callBacks.expenseList.limits == [20, 40, 60])
    }

    /// A new one, at the top, while one page is read: the page stays one page, followed.
    @Test func aNewExpenseShowsInThePagesRead() async {
        let callBacks = FakeExpenseCallBacks()
        let viewModel = ExpenseListViewModel(callBacks: callBacks)
        let expenses = numberedExpenses(25)

        callBacks.expenseList.send(expenses)
        await settle { viewModel.shownIds.count == 20 }

        let newest = expense(id: "newest", timestamp: "2026-12-01T12:00:00")
        callBacks.expenseList.send([newest] + expenses)
        await settle { viewModel.shownIds.first == "newest" }

        #expect(viewModel.shownIds == ["newest"] + expenses.prefix(19).map(\.id))
    }
}

/// `count` one-time expenses, newest first, a day apart.
func numberedExpenses(_ count: Int) -> [Expense] {
    (0..<count).map { index in
        let date = Calendar(identifier: .gregorian).date(byAdding: .day, value: -index, to: Date(timeIntervalSince1970: 1_793_000_000))!
        let day = date.formatted(.iso8601.year().month().day())

        return expense(id: "expense-\(index)", timestamp: day + "T12:00:00")
    }
}

extension ExpenseListViewModel {
    /// The expenses shown, in order.
    var shownIds: [String] {
        days.flatMap(\.expenses).map(\.id)
    }
}

extension GroupExpenseListViewModel {
    /// The expenses shown, in order.
    var shownIds: [String] {
        days.flatMap(\.expenses).map(\.id)
    }
}

@MainActor
struct ExpenseDetailViewModelTests {
    @Test func deletingTheExpenseClosesTheScreen() async {
        let callBacks = FakeExpenseCallBacks()
        callBacks.storedExpenses["expense"] = expense()
        let viewModel = ExpenseDetailViewModel(expenseId: "expense", callBacks: callBacks)

        await settle { viewModel.expense != nil }

        viewModel.delete()

        await settle { viewModel.isClosed }

        #expect(callBacks.deleted == ["expense"])
        #expect(viewModel.isClosed)
    }

    @Test func aFailedDeletionIsToldAndKeepsTheScreen() async {
        let callBacks = FakeExpenseCallBacks()
        callBacks.storedExpenses["expense"] = expense()
        callBacks.writeFailure = .serverUnreachable
        let viewModel = ExpenseDetailViewModel(expenseId: "expense", callBacks: callBacks)

        await settle { viewModel.expense != nil }

        viewModel.delete()

        await settle { viewModel.error != nil }

        #expect(viewModel.error == .serverUnreachable)
        #expect(!viewModel.isClosed)
        #expect(!viewModel.isBusy)
    }

    @Test func markingFlipsTheExpenseAsItIsShown() async {
        let callBacks = FakeExpenseCallBacks()
        callBacks.storedExpenses["expense"] = expense()
        let viewModel = ExpenseDetailViewModel(expenseId: "expense", callBacks: callBacks)

        await settle { viewModel.expense != nil }

        viewModel.toggleSettled()

        #expect(viewModel.isSettling)

        await settle { !viewModel.isSettling }

        #expect(callBacks.settled.map(\.1) == [true])
    }

    @Test func theBeneficiariesAreListedWithTheUserFirst() async {
        let callBacks = FakeExpenseCallBacks()
        callBacks.storedExpenses["expense"] = expense(
            beneficiaries: [Beneficiary(userUuid: bob, share: "5.00"), Beneficiary(userUuid: ada, share: "5.00"), Beneficiary(userUuid: me, share: "10.00")]
        )
        let viewModel = ExpenseDetailViewModel(expenseId: "expense", callBacks: callBacks)

        await settle { viewModel.names.name(of: bob) != nil }

        #expect(viewModel.beneficiaries.map(\.userUuid) == [me, ada, bob])
    }
}

@MainActor
struct ExpenseHistoryViewModelTests {
    @Test func theHistoryIsReadOnce() async {
        let callBacks = FakeExpenseCallBacks()
        let version = ExpenseVersion(revision: 1, expense: expense(), authorUuid: me, isCreation: true, madeAt: nil)
        callBacks.history = .versions([version])
        let viewModel = ExpenseHistoryViewModel(expenseId: "expense", callBacks: callBacks)

        await viewModel.load()

        #expect(viewModel.state == .loaded([version]))

        callBacks.history = .unavailable(.noInternet)

        await viewModel.load()

        #expect(viewModel.state == .loaded([version]))
    }

    @Test func aHistoryThatCannotBeReadSaysWhy() async {
        let callBacks = FakeExpenseCallBacks()
        callBacks.history = .unavailable(.timeout)
        let viewModel = ExpenseHistoryViewModel(expenseId: "expense", callBacks: callBacks)

        await viewModel.load()

        #expect(viewModel.state == .failed(.timeout))
    }
}
