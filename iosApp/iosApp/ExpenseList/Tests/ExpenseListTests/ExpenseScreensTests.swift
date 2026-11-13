import Foundation
import Testing

@testable import ExpenseList

/// What a one-time expense lets the user do, as Android's `ExpenseActionsTest` says it of one.
struct ExpenseActionsTests {
    @Test func aOneTimeExpenseIsEditedDeletedAndMarkedAsPaidBackByItsCreditor() {
        let actions = ExpenseActions(expense: expense(creditorUuid: me), selfUuid: me)

        #expect(actions.canEdit)
        #expect(actions.canDelete)
        #expect(actions.canSettle)
        #expect(actions.canShowHistory)
    }

    @Test func aOneTimeExpenseIsNeitherEditedNorDeletedNorMarkedByOneOfItsBeneficiaries() {
        let actions = ExpenseActions(expense: expense(creditorUuid: ada), selfUuid: me)

        #expect(!actions.canEdit)
        #expect(!actions.canDelete)
        #expect(!actions.canSettle)
        #expect(actions.canShowHistory)
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

        callBacks.expenseList.continuation.yield([])

        await settle { viewModel.hasReadExpenses }

        #expect(!viewModel.isLoading)

        await viewModel.refreshOnce()
        await viewModel.refreshOnce()

        #expect(callBacks.refreshCount == 1)
    }

    @Test func theCreditorsOfTheExpensesAreNamed() async {
        let callBacks = FakeExpenseCallBacks()
        let viewModel = ExpenseListViewModel(callBacks: callBacks)

        callBacks.expenseList.continuation.yield([expense(creditorUuid: ada), expense(id: "other", creditorUuid: "stranger")])

        await settle { viewModel.names.name(of: ada) != nil }

        #expect(viewModel.names.displayName(of: ada) == "Ada")
        #expect(viewModel.names.displayName(of: "stranger") == NameBook.unknownPerson)
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
