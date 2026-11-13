import Foundation
import Testing

@testable import ExpenseList

/// Dinner, paid 90.00 by the user, half of it each for them and Bob, in the group `trip`, as
/// `revision` of it says, with `title`.
private func dinner(revision: Int64, title: String, isDeleted: Bool = false) -> Expense {
    expense(
        title: title,
        amount: "90.00",
        creditorUuid: me,
        beneficiaries: [Beneficiary(userUuid: me, share: "45.00"), Beneficiary(userUuid: bob, share: "45.00")],
        revision: revision,
        groupId: trip,
        isDeleted: isDeleted,
        deletedByUuid: isDeleted ? bob : nil
    )
}

/// Takes an edit written after the version the server holds alone, as the server does, refusing any
/// other as another member's having come first, which the device then shows. `unseen` is what the
/// server holds that the device has yet to show, if anything: what it shows otherwise.
@MainActor
private func serve(_ callBacks: FakeExpenseCallBacks, unseen: Expense? = nil) {
    var unseen = unseen

    callBacks.onUpdate = { [weak callBacks] edit in
        guard let callBacks, let held = unseen ?? callBacks.storedExpenses[edit.id] else {
            return nil
        }

        guard edit.revision != held.revision else {
            return nil
        }

        callBacks.storedExpenses[edit.id] = held
        unseen = nil

        return .write(.changedMeanwhile)
    }
}

/// The form of a group's expense, as Android's form holds it with a group: in the group's currency,
/// paid by any of its members, shared among them, and saving nothing over a version it was not read
/// from.
@MainActor
struct GroupExpenseFormTests {
    private func newViewModel(
        _ callBacks: FakeExpenseCallBacks = FakeExpenseCallBacks(),
        expenseId: String? = nil
    ) async -> CreateExpenseViewModel {
        let viewModel = CreateExpenseViewModel(callBacks: callBacks, groupId: trip, expenseId: expenseId)

        await settle { !viewModel.isBeingRead }

        return viewModel
    }

    // MARK: - Currency

    @Test func aNewGroupExpenseIsReadInItsGroupsCurrencyBeforeAnythingIsTyped() async {
        let callBacks = FakeExpenseCallBacks()
        callBacks.heldGroupCurrency = yen
        let viewModel = CreateExpenseViewModel(callBacks: callBacks, groupId: trip)

        #expect(viewModel.isBeingRead)
        #expect(!viewModel.canSave)

        await settle { !viewModel.isBeingRead }

        #expect(callBacks.currencyGroups == [trip])
        #expect(viewModel.form.currency == yen)
        #expect(viewModel.typeAmount("12"))
        #expect(!viewModel.typeAmount("12."))
    }

    @Test func aNewOneTimeExpenseIsNeverBeingRead() {
        let viewModel = CreateExpenseViewModel(callBacks: FakeExpenseCallBacks())

        #expect(!viewModel.isBeingRead)
        #expect(!viewModel.isGroupExpense)
    }

    // MARK: - Payer

    @Test func theUserPaysANewGroupExpenseUntilAnotherMemberIsPicked() async {
        let viewModel = await newViewModel()

        #expect(viewModel.form.creditorUuid == me)

        viewModel.selectCreditor(bob)

        #expect(viewModel.form.creditorUuid == bob)

        await settle { viewModel.names.name(of: bob) != nil }

        #expect(viewModel.names.displayName(of: bob) == "Bob")
    }

    /// A member who is no contact gets a name once a group sync reads their profile, which can be
    /// with the form open: it shows as soon as it is read.
    @Test func aPayersNameReadAfterTheyWerePickedShows() async {
        let callBacks = FakeExpenseCallBacks()
        callBacks.names[cy] = nil
        let viewModel = await newViewModel(callBacks)

        viewModel.selectCreditor(cy)

        await settle { viewModel.names.displayName(of: cy) == NameBook.unknownPerson }

        #expect(viewModel.names.displayName(of: cy) == NameBook.unknownPerson)

        callBacks.setName("Cy", for: cy)

        await settle { viewModel.names.name(of: cy) != nil }

        #expect(viewModel.names.displayName(of: cy) == "Cy")
    }

    /// Read for the payer picked last alone, so that the name of one picked before never comes back,
    /// renamed as they may be since.
    @Test func theNameOfAPayerPickedBeforeIsNoLongerRead() async {
        let callBacks = FakeExpenseCallBacks()
        let viewModel = await newViewModel(callBacks)

        viewModel.selectCreditor(bob)

        await settle { viewModel.names.name(of: bob) != nil }

        viewModel.selectCreditor(cy)

        await settle { viewModel.names.name(of: cy) != nil }

        #expect(viewModel.names.name(of: bob) == nil)
        #expect(viewModel.names.displayName(of: cy) == "Cy")

        callBacks.setName("Robert", for: bob)

        await settle { viewModel.names.name(of: bob) != nil }

        #expect(viewModel.names.name(of: bob) == nil)
        #expect(viewModel.names.displayName(of: cy) == "Cy")

        callBacks.setName("Cy Young", for: cy)

        await settle { viewModel.names.name(of: cy) == "Cy Young" }

        #expect(viewModel.names.displayName(of: cy) == "Cy Young")
    }

    @Test func aOneTimeExpenseIsAlwaysPaidByTheUser() {
        let viewModel = CreateExpenseViewModel(callBacks: FakeExpenseCallBacks())

        viewModel.selectCreditor(ada)

        #expect(viewModel.form.creditorUuid == me)
    }

    @Test func thePayerAloneIsNoBeneficiary() async {
        let viewModel = await newViewModel()

        viewModel.selectCreditor(ada)
        viewModel.selectBeneficiaries([ada])
        viewModel.typeAmount("10")

        #expect(viewModel.error == .noBeneficiaryOtherThanCreditor)

        viewModel.selectCreditor(me)

        #expect(viewModel.error == nil)
    }

    // MARK: - Pickers

    @Test func thePickersListTheMembersOfTheGroupWithTheUserFirst() async {
        let callBacks = FakeExpenseCallBacks()
        let viewModel = await newViewModel(callBacks)

        await settle { !viewModel.users.isEmpty }

        #expect(viewModel.users.map(\.id) == [me, ada, bob, cy])
        #expect(!callBacks.searchedGroups.isEmpty)
        #expect(callBacks.searchedGroups.allSatisfy { $0 == trip })
    }

    /// Bob was picked first, and keeps the cents that do not divide evenly. A group holds no more
    /// members than an expense names, so the limit is not what stops it.
    @Test func selectAllAddsEveryMemberAfterThoseAlreadyPickedPastTheLimitOfOneByOne() async {
        let callBacks = FakeExpenseCallBacks()
        callBacks.maxBeneficiaries = 2
        let viewModel = await newViewModel(callBacks)

        await settle { !viewModel.users.isEmpty }

        viewModel.beginPickingBeneficiaries()

        #expect(viewModel.selectAllState == .none)

        viewModel.toggleSelection(userUuid: bob)

        #expect(viewModel.selectAllState == .some)

        viewModel.toggleSelectAll()

        #expect(viewModel.selectedUuids == [bob, me, ada, cy])
        #expect(viewModel.selectAllState == .all)

        viewModel.confirmSelection()

        #expect(viewModel.form.beneficiaries.map(\.userUuid) == [bob, me, ada, cy])
    }

    @Test func selectAllSelectsNobodyOnceEverybodyIs() async {
        let viewModel = await newViewModel()

        await settle { !viewModel.users.isEmpty }

        viewModel.beginPickingBeneficiaries()
        viewModel.toggleSelectAll()
        viewModel.toggleSelectAll()

        #expect(viewModel.selectedUuids.isEmpty)
        #expect(viewModel.selectAllState == .none)
    }

    @Test func selectAllIsOfferedForAGroupAloneAndNotWhileSearching() async {
        let viewModel = await newViewModel()

        await settle { !viewModel.users.isEmpty }

        #expect(viewModel.isSelectAllShown)

        viewModel.search("Bo")

        await settle { viewModel.users.count == 1 }

        #expect(!viewModel.isSelectAllShown)

        // Searched without the whitespace around it, which leaves nothing, so everybody is listed.
        viewModel.search("  ")

        await settle { viewModel.users.count == 4 }

        #expect(viewModel.users.count == 4)
        #expect(viewModel.isSelectAllShown)

        let oneTime = CreateExpenseViewModel(callBacks: FakeExpenseCallBacks())

        await settle { !oneTime.users.isEmpty }

        oneTime.toggleSelectAll()

        #expect(!oneTime.isSelectAllShown)
        #expect(oneTime.selectedUuids.isEmpty)
    }

    // MARK: - Saving

    /// In yen, the group's currency, which is not the one a one-time expense starts in.
    @Test func aGroupExpenseIsSavedInItsGroupAndItsCurrencyDatedNow() async {
        let callBacks = FakeExpenseCallBacks()
        callBacks.heldGroupCurrency = yen
        let viewModel = await newViewModel(callBacks)

        viewModel.updateTitle("Groceries")
        viewModel.selectCreditor(ada)
        viewModel.selectBeneficiaries([me, ada])
        viewModel.typeAmount("10")
        viewModel.save()

        await settle { viewModel.isSaved }

        #expect(callBacks.created.first?.groupId == trip)
        #expect(callBacks.created.first?.creditorUuid == ada)
        #expect(callBacks.created.first?.currency == yen)
        #expect(callBacks.created.first?.amount == "10")
        #expect(callBacks.created.first?.timestamp == callBacks.now)
        #expect(callBacks.created.first?.isSettlement == false)
    }

    @Test func anEditOfAGroupExpenseKeepsItsDateAndSendsTheRevisionItWasReadFrom() async {
        let callBacks = FakeExpenseCallBacks()
        callBacks.storedExpenses["expense"] = dinner(revision: 3, title: "Dinner")
        let viewModel = await newViewModel(callBacks, expenseId: "expense")

        #expect(viewModel.form.revision == 3)

        viewModel.updateTitle("Late dinner")
        viewModel.save()

        await settle { viewModel.isSaved }

        #expect(callBacks.updated.map(\.revision) == [3])
        #expect(callBacks.updated.first?.timestamp == "2026-11-10T12:00:00")
        #expect(callBacks.updated.first?.groupId == trip)
    }

    @Test func aGroupExpenseReadDeletedCannotBeSaved() async {
        let callBacks = FakeExpenseCallBacks()
        callBacks.storedExpenses["expense"] = dinner(revision: 3, title: "Dinner", isDeleted: true)
        let viewModel = await newViewModel(callBacks, expenseId: "expense")

        #expect(viewModel.form.isDeleted)

        viewModel.updateTitle("Late dinner")

        #expect(viewModel.hasChanges)
        #expect(!viewModel.canSave)
    }

    /// Bob's deletion reaches the device as the user types their edit. It took no revision, so the
    /// server would take the edit, which would then count in no balance: the form sends nothing, and
    /// says so rather than to try again.
    @Test func anEditOfAnExpenseAnotherMemberDeletedAsItWasOpenIsNotSent() async {
        let callBacks = FakeExpenseCallBacks()
        callBacks.storedExpenses["expense"] = dinner(revision: 3, title: "Dinner")
        let viewModel = await newViewModel(callBacks, expenseId: "expense")

        #expect(!viewModel.form.isDeleted)

        callBacks.storedExpenses["expense"] = dinner(revision: 3, title: "Dinner", isDeleted: true)
        viewModel.updateTitle("My dinner")
        viewModel.save()

        await settle { viewModel.saveError != nil }

        #expect(!viewModel.isSaved)
        #expect(!viewModel.isSaving)
        #expect(viewModel.form.isDeleted)
        #expect(callBacks.updated.isEmpty)
        #expect(viewModel.saveError == .deletedMeanwhile)
    }

    @Test func anEditRefusedForAnotherMembersVersionReadsTheFormAgainFromIt() async {
        let callBacks = FakeExpenseCallBacks()
        callBacks.storedExpenses["expense"] = dinner(revision: 3, title: "Dinner")
        serve(callBacks)
        let viewModel = await newViewModel(callBacks, expenseId: "expense")

        #expect(viewModel.form.revision == 3)

        // Bob's edit reaches the device as the user types theirs.
        callBacks.storedExpenses["expense"] = dinner(revision: 4, title: "Bob’s dinner")
        viewModel.updateTitle("My dinner")
        viewModel.save()

        await settle { viewModel.saveError != nil }

        #expect(!viewModel.isSaved)
        #expect(viewModel.saveError == .write(.changedMeanwhile))
        #expect(viewModel.form.revision == 4)
        #expect(viewModel.form.title == "Bob’s dinner")
        #expect(!viewModel.hasChanges)

        viewModel.saveError = nil
        viewModel.updateTitle("My dinner")
        viewModel.save()

        await settle { viewModel.isSaved }

        #expect(callBacks.updated.map(\.revision) == [3, 4])
    }

    /// Bob edited the expense, and deleted it, before the device showed either: the edit is sent, and
    /// refused for his version, which the form reads, a deletion it saves nothing over, and says so
    /// rather than to try again.
    @Test func anEditRefusedForAVersionOfADeletedExpenseReadsItAndSaysSo() async {
        let callBacks = FakeExpenseCallBacks()
        callBacks.storedExpenses["expense"] = dinner(revision: 3, title: "Dinner")
        let viewModel = await newViewModel(callBacks, expenseId: "expense")

        serve(callBacks, unseen: dinner(revision: 4, title: "Bob’s dinner", isDeleted: true))
        viewModel.updateTitle("My dinner")
        viewModel.save()

        await settle { viewModel.saveError != nil }

        #expect(!viewModel.isSaved)
        #expect(viewModel.form.isDeleted)
        #expect(viewModel.form.revision == 4)
        #expect(callBacks.updated.map(\.revision) == [3])
        #expect(viewModel.saveError == .deletedMeanwhile)
        #expect(!viewModel.canSave)
    }
}

/// A group's list of expenses, and what its rows show of them.
@MainActor
struct GroupExpenseListViewModelTests {
    @Test func everyExpenseOfTheGroupIsListedByDayDeletedOnesIncluded() async {
        let callBacks = FakeExpenseCallBacks()
        let viewModel = GroupExpenseListViewModel(groupId: trip, callBacks: callBacks)

        #expect(viewModel.isLoading)

        callBacks.groupExpenseList.continuation.yield([
            expense(id: "3", timestamp: "2026-11-10T20:00:00", groupId: trip),
            expense(id: "2", timestamp: "2026-11-10T08:00:00", groupId: trip, isDeleted: true, deletedByUuid: bob),
            settlement(id: "1", timestamp: "2026-11-09T18:00:00", isDeleted: true),
        ])

        await settle { viewModel.hasReadExpenses }

        #expect(!viewModel.isLoading)
        #expect(callBacks.followedGroups == [trip])
        #expect(viewModel.days.map(\.id) == ["2026-11-10", "2026-11-09"])
        #expect(viewModel.days.map { $0.expenses.map(\.id) } == [["3", "2"], ["1"]])
    }

    @Test func anEmptyGroupIsNoLongerLoadingOnceRead() async {
        let callBacks = FakeExpenseCallBacks()
        let viewModel = GroupExpenseListViewModel(groupId: trip, callBacks: callBacks)

        callBacks.groupExpenseList.continuation.yield([])

        await settle { viewModel.hasReadExpenses }

        #expect(!viewModel.isLoading)
        #expect(viewModel.days.isEmpty)
    }

    /// "Bob paid Me", and nothing of it is owed by anybody.
    @Test func aSettlementNamesWhoPaidAndWhoWasPaidBackAndShowsNoShare() async {
        let callBacks = FakeExpenseCallBacks()
        let viewModel = GroupExpenseListViewModel(groupId: trip, callBacks: callBacks)

        callBacks.groupExpenseList.continuation.yield([settlement()])

        await settle { viewModel.names.name(of: bob) != nil && viewModel.names.name(of: me) != nil }

        let paidBack = viewModel.days[0].expenses[0]

        #expect(paidBack.settlementRecipientUuid == me)
        #expect(viewModel.names.displayName(of: paidBack.creditorUuid) == "Bob")
        #expect(paidBack.shownBalance(selfUuid: me) == nil)
    }

    @Test func anOrdinaryGroupExpenseShowsTheUsersShare() {
        let groceries = expense(creditorUuid: ada, groupId: trip)

        #expect(groceries.settlementRecipientUuid == nil)
        #expect(groceries.shownBalance(selfUuid: me) == Decimal(-10))
    }

    @Test func anExpenseIsAddedOnlyWhileTheUserIsInTheGroup() async {
        let callBacks = FakeExpenseCallBacks()
        let viewModel = GroupExpenseListViewModel(groupId: trip, callBacks: callBacks)

        // Not before it is known either.
        #expect(!viewModel.canAddExpense)

        await settle { viewModel.isGroupLeft != nil }

        #expect(callBacks.leftGroups == [trip])
        #expect(viewModel.canAddExpense)

        callBacks.setGroupLeft(true)

        await settle { viewModel.isGroupLeft == true }

        #expect(!viewModel.canAddExpense)
    }

    @Test func pullingToRefreshBringsTheGroupUpToDate() async {
        let callBacks = FakeExpenseCallBacks()
        let viewModel = GroupExpenseListViewModel(groupId: trip, callBacks: callBacks)

        await viewModel.refresh()

        #expect(callBacks.refreshedGroups == [trip])
    }
}

/// A group expense's screen.
@MainActor
struct GroupExpenseDetailViewModelTests {
    @Test func restoringTheExpenseClosesTheScreen() async {
        let callBacks = FakeExpenseCallBacks()
        callBacks.storedExpenses["expense"] = expense(creditorUuid: ada, groupId: trip, isDeleted: true, deletedByUuid: bob)
        let viewModel = ExpenseDetailViewModel(expenseId: "expense", callBacks: callBacks)

        await settle { viewModel.actions != nil }

        #expect(viewModel.actions?.canRestore == true)

        viewModel.restore()

        #expect(viewModel.isRestoring)
        #expect(viewModel.isBusy)

        await settle { viewModel.isClosed }

        #expect(callBacks.restored == ["expense"])
        #expect(viewModel.isClosed)
    }

    @Test func aFailedRestorationIsToldAndKeepsTheScreen() async {
        let callBacks = FakeExpenseCallBacks()
        callBacks.storedExpenses["expense"] = expense(groupId: trip, isDeleted: true, deletedByUuid: bob)
        callBacks.writeFailure = .groupLeft
        let viewModel = ExpenseDetailViewModel(expenseId: "expense", callBacks: callBacks)

        await settle { viewModel.actions != nil }

        viewModel.restore()

        await settle { viewModel.error != nil }

        #expect(viewModel.error == .groupLeft)
        #expect(!viewModel.isClosed)
        #expect(!viewModel.isBusy)
    }

    @Test func deletingAGroupExpenseClosesTheScreen() async {
        let callBacks = FakeExpenseCallBacks()
        callBacks.storedExpenses["expense"] = expense(creditorUuid: ada, groupId: trip)
        let viewModel = ExpenseDetailViewModel(expenseId: "expense", callBacks: callBacks)

        await settle { viewModel.actions != nil }

        #expect(viewModel.actions?.canDelete == true)

        viewModel.delete()

        await settle { viewModel.isClosed }

        #expect(callBacks.deleted == ["expense"])
    }

    @Test func whoDeletedTheExpenseIsNamed() async {
        let callBacks = FakeExpenseCallBacks()
        callBacks.storedExpenses["expense"] = expense(groupId: trip, isDeleted: true, deletedByUuid: bob)
        let viewModel = ExpenseDetailViewModel(expenseId: "expense", callBacks: callBacks)

        await settle { viewModel.names.name(of: bob) != nil }

        #expect(viewModel.names.displayName(of: bob) == "Bob")
    }

    /// A sync can read the removal with the screen open.
    @Test func beingRemovedFromTheGroupWithTheScreenOpenLeavesTheExpenseReadOnly() async {
        let callBacks = FakeExpenseCallBacks()
        callBacks.storedExpenses["expense"] = expense(creditorUuid: ada, groupId: trip)
        let viewModel = ExpenseDetailViewModel(expenseId: "expense", callBacks: callBacks)

        await settle { viewModel.actions != nil }

        #expect(viewModel.actions?.canEdit == true)
        #expect(viewModel.actions?.canDelete == true)

        callBacks.setGroupLeft(true)

        await settle { viewModel.isGroupLeft == true }

        #expect(
            viewModel.actions
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

    /// That of the expense, read from it, whichever group's screen it was opened from.
    @Test func whetherTheUserIsStillInTheExpensesOwnGroupIsFollowed() async {
        let callBacks = FakeExpenseCallBacks()
        callBacks.storedExpenses["expense"] = expense(creditorUuid: ada, groupId: "flat")
        let viewModel = ExpenseDetailViewModel(expenseId: "expense", callBacks: callBacks)

        await settle { viewModel.isGroupLeft != nil }

        #expect(viewModel.isGroupLeft == false)
        #expect(callBacks.leftGroups == ["flat"])
    }

    @Test func aOneTimeExpenseIsInNoGroupToLeave() async {
        let callBacks = FakeExpenseCallBacks()
        callBacks.storedExpenses["expense"] = expense()
        let viewModel = ExpenseDetailViewModel(expenseId: "expense", callBacks: callBacks)

        await settle { viewModel.actions != nil }

        #expect(viewModel.isGroupLeft == false)
        #expect(callBacks.leftGroups.isEmpty)
        #expect(viewModel.actions?.canSettle == true)
    }
}
