import Foundation
import Testing

@testable import GroupList

/// What the groups tab shows, as Android's `GroupListViewModel` holds it.
@MainActor
struct GroupListViewModelTests {
    /// Left and deleted ones included, which stay read-only.
    @Test func theGroupsAreListedInTheOrderTheSharedModuleGives() async {
        let callBacks = FakeGroupCallBacks()
        let groups = [
            GroupSummary(id: "2", title: "Flat share", description: "", isLeft: true, isDeleted: true),
            GroupSummary(id: "1", title: "Ski trip", description: "Chalet", isLeft: false, isDeleted: false),
        ]
        callBacks.groupList.send(groups)
        let viewModel = GroupListViewModel(callBacks: callBacks)

        #expect(viewModel.groups == nil)

        await settle { viewModel.groups != nil }

        #expect(viewModel.groups == groups)
    }

    @Test func aRefreshAsksTheSharedModule() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = GroupListViewModel(callBacks: callBacks)

        await viewModel.refresh()

        #expect(callBacks.refreshCount == 1)
    }
}

/// What a group's screen shows beyond its tabs, as Android's `GroupDetailViewModelTest` says it:
/// how many of its expenses this device cannot read yet, and, on iOS, that the group is gone.
@MainActor
struct GroupDetailViewModelTests {
    @Test func refreshingAGroupSyncsIt() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = GroupDetailViewModel(groupId: groupId, callBacks: callBacks)

        await viewModel.refresh()

        #expect(callBacks.refreshedGroups == [groupId])
    }

    /// The group shows what this device has of it while the server is asked.
    @Test func theGroupShowsBeforeItsExpensesAreSynced() async {
        let callBacks = FakeGroupCallBacks()
        let gate = Gate()
        callBacks.gate = gate
        let viewModel = GroupDetailViewModel(groupId: groupId, callBacks: callBacks)

        let sync = Task {
            await viewModel.refresh()
        }

        await settle { viewModel.group != nil }

        // The sync is still held back.
        #expect(viewModel.group?.id == groupId)
        #expect(viewModel.group?.title == "Ski trip")

        gate.open()
        await sync.value

        #expect(callBacks.refreshedGroups == [groupId])
    }

    /// Sealed under a key this device has yet to get: the group's screen says its balances leave
    /// them out.
    @Test func theGroupShowsHowManyOfItsExpensesCannotBeReadYet() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = GroupDetailViewModel(groupId: groupId, callBacks: callBacks)

        await settle { viewModel.group != nil }

        #expect(viewModel.unreadableExpenseCount == 0)

        callBacks.unreadable.send(2)
        await settle { viewModel.unreadableExpenseCount == 2 }

        #expect(viewModel.unreadableExpenseCount == 2)
    }

    /// Its owner deleted it from here, the user deleted it from this device, or the server refused
    /// its creation: the split view would keep showing it otherwise.
    @Test func aGroupTheDeviceHoldsNoMoreIsGone() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = GroupDetailViewModel(groupId: groupId, callBacks: callBacks)

        await settle { viewModel.group != nil }
        #expect(!viewModel.isGone)

        callBacks.shownGroup.send(nil)
        await settle { viewModel.isGone }

        #expect(viewModel.isGone)
    }

    /// A settlement is an expense, which a group the user is no longer in takes no more of.
    @Test func aSettlementIsRecordedInAGroupTheUserIsStillInAlone() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = GroupDetailViewModel(groupId: groupId, callBacks: callBacks)

        #expect(!viewModel.canSettle)

        await settle { viewModel.group != nil }
        #expect(viewModel.canSettle)

        callBacks.shownGroup.send(groupDetails(isLeft: true))
        await settle { viewModel.group?.isLeft == true }

        #expect(!viewModel.canSettle)
    }

    /// Back to the group after a while away, an expense read or the app in the background: its
    /// expenses are synced again, and what it shows is still followed once. On iOS, showing the
    /// screen again runs its task again, which is the refresh, while its view model, followed from
    /// the start, lives as long as the screen: Android's view model instead follows only while the
    /// screen collects it.
    @Test func comingBackToTheGroupSyncsItAgainAndFollowsItOnceStill() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = GroupDetailViewModel(groupId: groupId, callBacks: callBacks)

        // What the screen's task runs each time it shows.
        for _ in 0..<3 {
            await viewModel.refresh()
        }

        await settle { viewModel.group != nil }

        #expect(callBacks.shownGroup.followerCount == 1)
        #expect(callBacks.unreadable.followerCount == 1)
        #expect(callBacks.refreshedGroups == [groupId, groupId, groupId])
    }
}

/// The versions the history gives, the one the group is at first.
private let versions = [
    GroupVersion(
        revision: 2,
        title: "Ski trip 2027",
        description: "",
        isCreation: false,
        isCurrent: true,
        revisedAt: Date(timeIntervalSince1970: 1_793_214_127.004)
    ),
    GroupVersion(
        revision: 1,
        title: "Ski trip",
        description: "Chalet and lift passes",
        isCreation: true,
        isCurrent: false,
        revisedAt: Date(timeIntervalSince1970: 1_793_095_200)
    ),
]

/// What the history screen shows of the group's history, as Android's `GroupHistoryViewModelTest`
/// says it: read from the server as the screen first shows, and again when the user asks once it
/// could not be.
@MainActor
struct GroupHistoryViewModelTests {
    @Test func theHistoryIsReadOnceTheScreenShows() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.histories = [.versions(versions)]
        let viewModel = GroupHistoryViewModel(groupId: groupId, callBacks: callBacks)

        await settle()

        #expect(viewModel.state == .loading)
        #expect(callBacks.historyReads.isEmpty)

        viewModel.load()
        await settle { viewModel.state != .loading }

        #expect(viewModel.state == .loaded(versions))
        #expect(callBacks.historyReads == [groupId])
    }

    /// Coming back to the screen, from another app say, reads nothing again.
    @Test func theHistoryIsReadOnceHoweverOftenTheScreenShows() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.histories = [.versions(versions)]
        let viewModel = GroupHistoryViewModel(groupId: groupId, callBacks: callBacks)

        viewModel.load()
        await settle { viewModel.state != .loading }
        viewModel.load()
        await settle()

        #expect(callBacks.historyReads == [groupId])
    }

    @Test func aHistoryThatCouldNotBeReadTellsWhy() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.histories = [.unavailable(.deviceLocked)]
        let viewModel = GroupHistoryViewModel(groupId: groupId, callBacks: callBacks)

        viewModel.load()
        await settle { viewModel.state != .loading }

        #expect(viewModel.state == .failed(.deviceLocked))
    }

    @Test func aHistoryThatCouldNotBeReadIsReadAgainOnRetry() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.histories = [.unavailable(.failure(.noInternet)), .versions(versions)]
        let viewModel = GroupHistoryViewModel(groupId: groupId, callBacks: callBacks)

        viewModel.load()
        await settle { viewModel.state != .loading }

        #expect(viewModel.state == .failed(.failure(.noInternet)))

        let gate = Gate()
        callBacks.gate = gate
        viewModel.retry()

        // Shown loading again while it is read.
        #expect(viewModel.state == .loading)

        gate.open()
        await settle { viewModel.state != .loading }

        #expect(viewModel.state == .loaded(versions))
        #expect(callBacks.historyReads == [groupId, groupId])
    }

    /// Only once it could not be read.
    @Test func aHistoryReadIsNotReadAgain() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.histories = [.versions(versions)]
        let viewModel = GroupHistoryViewModel(groupId: groupId, callBacks: callBacks)

        viewModel.load()
        await settle { viewModel.state != .loading }
        viewModel.retry()
        await settle()

        #expect(callBacks.historyReads == [groupId])
    }
}

/// Who owes whom in a group, as its Balance tab shows it, as Android's `GroupBalanceScreen` and
/// `BalanceNamesTest` say it: everybody who comes to anything, by what they come to, former members
/// told apart.
@MainActor
struct BalanceTests {
    private func balances(_ netAmounts: [String: String], memberUuids: Set<String> = [me, ada, bob], debts: [Debt] = []) -> GroupBalances {
        GroupBalances(netAmounts: netAmounts, memberUuids: memberUuids, debts: debts, currencySymbol: "€")
    }

    @Test func everybodyIsListedByWhatTheyComeToTheMostOwedFirst() {
        let rows = BalanceRow.rows(of: balances([me: "-5.50", ada: "30.00", bob: "-24.50"]))

        #expect(rows.map(\.id) == [ada, me, bob])
        #expect(rows.map(\.amount) == ["30.00", "-5.50", "-24.50"])
    }

    /// As numbers, not as text, which would put "9" above "10".
    @Test func amountsAreComparedAsNumbers() {
        let rows = BalanceRow.rows(of: balances([me: "9.00", ada: "10.00", bob: "-19.00"]))

        #expect(rows.map(\.id) == [ada, me, bob])
    }

    @Test func nobodyWhoComesToZeroIsListed() {
        let rows = BalanceRow.rows(of: balances([me: "0.00", ada: "0", bob: "-0.00"]))

        #expect(rows.isEmpty)
    }

    /// Whom the creator removed, or who left: the expenses naming them stay, and so does their name.
    @Test func aFormerMemberIsToldApart() {
        let rows = BalanceRow.rows(of: balances([me: "10.00", carol: "-10.00"], memberUuids: [me, ada]))

        #expect(rows.map(\.isFormerMember) == [false, true])
    }

    @Test func eachBarIsAsLongAgainstTheLongestAsWhatItComesTo() {
        let rows = BalanceRow.rows(of: balances([me: "-10.00", ada: "40.00", bob: "-30.00"]))

        #expect(rows.map(\.fraction) == [1, 0.25, 0.75])
    }

    /// Shown before, a row would have no name.
    @Test func theBalancesShowOnceTheNamesTheyNameAreRead() async {
        let callBacks = FakeGroupCallBacks()
        let gate = Gate()
        callBacks.namesGate = gate
        callBacks.groupBalances.send(balances([me: "10.00", ada: "-10.00"]))
        let viewModel = GroupBalancesViewModel(groupId: groupId, callBacks: callBacks)

        await settle()

        #expect(viewModel.state == .loading)
        #expect(viewModel.balanceRows.isEmpty)

        gate.open()
        await settle { viewModel.state != .loading }

        #expect(viewModel.balanceRows.map(viewModel.name(of:)) == ["Me", "Ada"])
    }

    /// Worked out again with somebody new in them, they show as they were until that person's name
    /// is read.
    @Test func balancesNamingSomebodyNewShowOnceTheirNameIsRead() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.groupBalances.send(balances([me: "10.00", ada: "-10.00"]))
        let viewModel = GroupBalancesViewModel(groupId: groupId, callBacks: callBacks)

        await settle { viewModel.state != .loading }

        let gate = Gate()
        callBacks.namesGate = gate
        callBacks.groupBalances.send(balances([me: "10.00", ada: "-5.00", bob: "-5.00"]))
        await settle()

        #expect(viewModel.balanceRows.map(\.id) == [me, ada])

        gate.open()
        await settle { viewModel.balanceRows.count == 3 }

        #expect(viewModel.balanceRows.map(viewModel.name(of:)) == ["Me", "Ada", "Bob"])
    }

    @Test func theBalancesAreNamedAsNamesAreRead() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.names.send([me: "Me"])
        callBacks.groupBalances.send(balances([me: "10.00", ada: "-10.00"]))
        let viewModel = GroupBalancesViewModel(groupId: groupId, callBacks: callBacks)

        await settle { !viewModel.names.read.isEmpty }

        #expect(viewModel.balanceRows.map { viewModel.names.name(of: $0.id) } == ["Me", nil])

        callBacks.names.send([me: "Me", ada: "Ada"])
        await settle { viewModel.names.name(of: ada) != nil }

        #expect(viewModel.balanceRows.map(viewModel.name(of:)) == ["Me", "Ada"])
    }

    @Test func aFormerMemberIsNamedAsSuch() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.groupBalances.send(balances([me: "10.00", carol: "-10.00"], memberUuids: [me, ada]))
        let viewModel = GroupBalancesViewModel(groupId: groupId, callBacks: callBacks)

        await settle { viewModel.names.name(of: carol) != nil }

        let names = viewModel.balanceRows.map(viewModel.name(of:))
        let carolName = "Carol"

        #expect(names[0] == "Me")
        #expect(names[1] == String(localized: "\(carolName) (former member)", bundle: .module))
    }

    @Test func balancesThatCannotBeWorkedOutAreToldSo() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = GroupBalancesViewModel(groupId: groupId, callBacks: callBacks)

        await settle { viewModel.state != .loading }

        #expect(viewModel.state == .unavailable)
        #expect(viewModel.balanceRows.isEmpty)
        #expect(viewModel.debts.isEmpty)
    }

    @Test func eachAmountIsWrittenNextToTheGroupsCurrencySymbol() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.groupBalances.send(balances([me: "10.00", ada: "-10.00"]))
        let viewModel = GroupBalancesViewModel(groupId: groupId, callBacks: callBacks)

        await settle { viewModel.state != .loading }

        #expect(viewModel.amountLabel("-10.00") == "-10.00 €")
    }
}

/// What becomes of the user recording that somebody paid them back, as Android's
/// `GroupSettleUpViewModelTest` says it, and how each debt is worded for them.
@MainActor
struct SettleUpTests {
    private let adaOwesMe = Debt(debtorUuid: ada, creditorUuid: me, amount: "20.00")

    private func settleUp(_ callBacks: FakeGroupCallBacks) async -> GroupBalancesViewModel {
        callBacks.groupBalances.send(
            GroupBalances(
                netAmounts: [me: "20.00", ada: "-20.00", bob: "5.00", carol: "-5.00"],
                memberUuids: [me, ada, bob, carol],
                debts: [adaOwesMe, Debt(debtorUuid: me, creditorUuid: bob, amount: "5.00"), Debt(debtorUuid: carol, creditorUuid: bob, amount: "5.00")],
                currencySymbol: "€"
            )
        )

        let viewModel = GroupBalancesViewModel(groupId: groupId, callBacks: callBacks)

        await settle { viewModel.names.known.count == 4 }

        return viewModel
    }

    /// Every debt in the group, not only the user's.
    @Test func everyDebtIsListed() async {
        let viewModel = await settleUp(FakeGroupCallBacks())

        #expect(viewModel.debts.map { $0.side(for: viewModel.selfUuid) } == [.owedToUser, .owedByUser, .betweenOthers])
    }

    /// Each worded its own way for the user reading it, with names and the amount in it.
    @Test func eachDebtIsWordedForTheUser() async {
        let viewModel = await settleUp(FakeGroupCallBacks())
        let sentences = viewModel.debts.map(viewModel.sentence(for:))
        let (adaName, bobName, carolName, twenty, five) = ("Ada", "Bob", "Carol", "20.00 €", "5.00 €")

        #expect(Set(sentences).count == 3)
        #expect(sentences[0] == String(localized: "\(adaName) owes you \(twenty)", bundle: .module))
        #expect(sentences[1] == String(localized: "You owe \(bobName) \(five)", bundle: .module))
        #expect(sentences[2] == String(localized: "\(carolName) owes \(bobName) \(five)", bundle: .module))
    }

    /// As Android's `SettleUpNamesTest` says it: somebody named by no profile yet is named once a
    /// sync reads one.
    @Test func aNameReadAfterTheDebtsShowedShows() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.names.send([me: "Me"])
        callBacks.groupBalances.send(
            GroupBalances(netAmounts: [me: "20.00", ada: "-20.00"], memberUuids: [me, ada], debts: [adaOwesMe], currencySymbol: "€")
        )
        let viewModel = GroupBalancesViewModel(groupId: groupId, callBacks: callBacks)
        let (someoneElse, name, twenty) = (MemberNames.someoneElse, "Ada", "20.00 €")

        await settle { viewModel.state != .loading }

        #expect(viewModel.sentence(for: adaOwesMe) == String(localized: "\(someoneElse) owes you \(twenty)", bundle: .module))

        callBacks.names.send([me: "Me", ada: "Ada"])
        await settle { viewModel.names.name(of: ada) != nil }

        #expect(viewModel.sentence(for: adaOwesMe) == String(localized: "\(name) owes you \(twenty)", bundle: .module))
    }

    /// Nobody to name: waiting on names would never tell anything, and the tab would load forever.
    @Test func aGroupAllSettledUpIsToldSoAtOnce() async {
        let callBacks = FakeGroupCallBacks()
        // Never opened: no name is ever read.
        callBacks.namesGate = Gate()
        let settled = GroupBalances(netAmounts: [me: "0.00", ada: "0.00"], memberUuids: [me, ada], debts: [], currencySymbol: "€")
        callBacks.groupBalances.send(settled)
        let viewModel = GroupBalancesViewModel(groupId: groupId, callBacks: callBacks)

        await settle { viewModel.state != .loading }

        #expect(viewModel.state == .content(settled))
        #expect(viewModel.debts.isEmpty)
        #expect(viewModel.balanceRows.isEmpty)
    }

    /// Each one is an expense of its own: a second tap would record the debt paid back twice.
    @Test func aSettlementTappedAgainWhileOnItsWayIsRecordedOnce() async {
        let callBacks = FakeGroupCallBacks()
        let gate = Gate()
        let viewModel = await settleUp(callBacks)

        callBacks.gate = gate
        viewModel.settle(adaOwesMe)
        viewModel.settle(adaOwesMe)

        #expect(viewModel.settlingDebtId == adaOwesMe.id)

        gate.open()
        await settle { viewModel.settlingDebtId == nil }

        #expect(callBacks.settlements.map(\.debtorUuid) == [ada])
        #expect(callBacks.settlements.map(\.amount) == ["20.00"])
    }

    @Test func aSettlementThatWasNotRecordedTellsWhy() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.settlementFailure = .noInternet
        let viewModel = await settleUp(callBacks)

        viewModel.settle(adaOwesMe)
        await settle { viewModel.settlementError != nil }

        #expect(viewModel.settlementError == .noInternet)
    }

    /// The debt is still listed, for the user to record again once the first answer came.
    @Test func aSettlementThatWasNotRecordedCanBeRecordedAgain() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.settlementFailure = .serverUnreachable
        let viewModel = await settleUp(callBacks)

        viewModel.settle(adaOwesMe)
        await settle { viewModel.settlingDebtId == nil && viewModel.settlementError != nil }
        viewModel.settle(adaOwesMe)
        await settle { callBacks.settlements.count == 2 }

        #expect(callBacks.settlements.map(\.debtorUuid) == [ada, ada])
    }

    @Test func aSettlementRecordedTellsNothing() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = await settleUp(callBacks)

        viewModel.settle(adaOwesMe)
        await settle { viewModel.settlingDebtId == nil }

        #expect(viewModel.settlementError == nil)
        #expect(callBacks.settlements.count == 1)
    }

    /// Only the creditor records a debt paid back.
    @Test func aDebtNotOwedToTheUserIsNotSettledByThem() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = await settleUp(callBacks)

        viewModel.settle(viewModel.debts[1])
        viewModel.settle(viewModel.debts[2])
        await settle()

        #expect(callBacks.settlements.isEmpty)
    }
}
