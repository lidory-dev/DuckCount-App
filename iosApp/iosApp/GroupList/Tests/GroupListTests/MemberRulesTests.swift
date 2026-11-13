import Foundation
import Testing

@testable import GroupList

private let shown = GroupInformation(title: "Ski trip", description: "Chalet and lift passes")

/// A form, the group to create's unless it shows `shown`.
private func form(
    memberUuids: [String],
    title: String = "Trip",
    description: String = "",
    shown: GroupInformation? = nil
) -> GroupForm {
    GroupForm(title: title, description: description, currency: euro, memberUuids: memberUuids, shown: shown)
}

/// What the group form lists once members are picked, see `keptMembers`, and when it can be
/// confirmed, see `GroupForm.canConfirm`, for a group to create or one being edited, as Android's
/// `CreateGroupFormTest` says it.
struct CreateGroupFormTests {
    /// Picking nobody once left the user out of their own group, which could then not be created.
    @Test func theUserStaysAMemberWhenNobodyIsPicked() {
        #expect(keptMembers(memberUuids: [me, ada, bob], selfUuid: me, selectedUuids: []) == [me])
    }

    @Test func theUserAndWhoeverIsStillPickedAreKept() {
        #expect(keptMembers(memberUuids: [me, ada, bob, carol], selfUuid: me, selectedUuids: [carol, ada]) == [me, ada, carol])
    }

    @Test func whoeverIsPickedAndNotListedYetIsLeftToBeAdded() {
        #expect(keptMembers(memberUuids: [me, ada], selfUuid: me, selectedUuids: [ada, bob]) == [me, ada])
    }

    @Test func theUserAloneCannotConfirmAGroup() {
        #expect(!form(memberUuids: [me]).canConfirm(maxTitleLength: 50, maxDescriptionLength: 1000))
    }

    @Test func theUserAndSomebodyElseWithATitleCanConfirmAGroup() {
        #expect(form(memberUuids: [me, ada]).canConfirm(maxTitleLength: 50, maxDescriptionLength: 1000))
    }

    /// Its members are changed from its settings, so the form lists none of them.
    @Test func aGroupBeingEditedCanBeConfirmedWithNoMembersListed() {
        #expect(form(memberUuids: [], shown: shown).canConfirm(maxTitleLength: 50, maxDescriptionLength: 1000))
    }

    @Test func aGroupBeingEditedWithNoTitleCannotBeConfirmed() {
        #expect(!form(memberUuids: [], title: "", shown: shown).canConfirm(maxTitleLength: 50, maxDescriptionLength: 1000))
    }

    /// It would wake every other member for nothing, and push the oldest version out of the history.
    @Test func aGroupBeingEditedToWhatItShowsCannotBeConfirmed() {
        let edited = form(memberUuids: [], title: shown.title, description: shown.description, shown: shown)

        #expect(!edited.canConfirm(maxTitleLength: 50, maxDescriptionLength: 1000))
    }

    @Test func aGroupBeingEditedWithItsTitleAloneChangedCanBeConfirmed() {
        let edited = form(memberUuids: [], title: "Ski trip 2027", description: shown.description, shown: shown)

        #expect(edited.canConfirm(maxTitleLength: 50, maxDescriptionLength: 1000))
    }

    @Test func aGroupBeingEditedWithItsDescriptionAloneChangedCanBeConfirmed() {
        let edited = form(memberUuids: [], title: shown.title, description: "", shown: shown)

        #expect(edited.canConfirm(maxTitleLength: 50, maxDescriptionLength: 1000))
    }

    /// Saved without it, it would be the same title and description again.
    @Test func aGroupBeingEditedWithNothingButWhitespaceAddedAroundItCannotBeConfirmed() {
        let edited = form(memberUuids: [], title: " \(shown.title) ", description: "\(shown.description)\n\n", shown: shown)

        #expect(!edited.canConfirm(maxTitleLength: 50, maxDescriptionLength: 1000))
    }

    @Test func aTitleOfNothingButSpacesCannotBeConfirmed() {
        #expect(!form(memberUuids: [me, ada], title: "   ").canConfirm(maxTitleLength: 50, maxDescriptionLength: 1000))
        #expect(!form(memberUuids: [], title: "   ", shown: shown).canConfirm(maxTitleLength: 50, maxDescriptionLength: 1000))
    }

    /// Counted as it is saved, without the whitespace around it.
    @Test func aTitlePastItsLimitByTheWhitespaceAroundItAloneCanBeConfirmed() {
        let padded = form(memberUuids: [me, ada], title: " \(String(repeating: "a", count: 50)) ")

        #expect(padded.canConfirm(maxTitleLength: 50, maxDescriptionLength: 1000))
    }

    @Test func aDescriptionPastItsLimitByTheWhitespaceAroundItAloneCanBeConfirmed() {
        let padded = form(memberUuids: [me, ada], description: "\(String(repeating: "a", count: 1000))\n")

        #expect(padded.canConfirm(maxTitleLength: 50, maxDescriptionLength: 1000))
    }

    @Test func aGroupWithNoTitleCannotBeConfirmed() {
        #expect(!form(memberUuids: [me, ada], title: "").canConfirm(maxTitleLength: 50, maxDescriptionLength: 1000))
    }

    @Test func aTitlePastItsLimitCannotBeConfirmed() {
        let long = form(memberUuids: [me, ada], title: String(repeating: "a", count: 51))

        #expect(!long.canConfirm(maxTitleLength: 50, maxDescriptionLength: 1000))
    }

    @Test func aDescriptionPastItsLimitCannotBeConfirmed() {
        let long = form(memberUuids: [me, ada], description: String(repeating: "a", count: 1001))

        #expect(!long.canConfirm(maxTitleLength: 50, maxDescriptionLength: 1000))
    }

    /// As a receiving device checks it, and Android counts it: an emoji is two units, not one
    /// character, and a title its owner could save would be one the others cannot open.
    @Test func textIsCountedInUtf16Units() {
        let ducks = form(memberUuids: [me, ada], title: String(repeating: "🦆", count: 26))

        #expect(ducks.titleLength == 52)
        #expect(!ducks.canConfirm(maxTitleLength: 50, maxDescriptionLength: 1000))
        #expect(form(memberUuids: [me, ada], title: String(repeating: "🦆", count: 25)).canConfirm(maxTitleLength: 50, maxDescriptionLength: 1000))
    }
}

/// Picking a group's members one by one never goes past the limit, the user who creates it counting
/// against it, see `memberCount`, `isSelectable` and `toggledSelection`, as Android's
/// `MemberSelectionTest` says it.
struct MemberSelectionTests {
    @Test func theUserCountsAsAMemberWithNobodyPicked() {
        #expect(memberCount(selectedUuids: []) == 1)
    }

    @Test func theUserCountsOnTopOfWhoeverIsPicked() {
        #expect(memberCount(selectedUuids: [ada, bob]) == 3)
    }

    @Test func pickingSomeoneBelowTheLimitAddsThem() {
        #expect(toggledSelection(selected: [ada], uuid: bob, maxMembers: 3) == [ada, bob])
    }

    /// Two picked and the user make three.
    @Test func pickingSomeoneOnceTheUserFillsTheLastPlaceDoesNothing() {
        #expect(toggledSelection(selected: [ada, bob], uuid: carol, maxMembers: 3) == [ada, bob])
    }

    @Test func unpickingSomeoneAtTheLimitTakesThemOut() {
        #expect(toggledSelection(selected: [ada, bob], uuid: bob, maxMembers: 3) == [ada])
    }

    @Test func aRowNotPickedIsEnabledBelowTheLimit() {
        #expect(isSelectable(uuid: carol, selectedUuids: [ada], maxMembers: 3))
    }

    @Test func aRowNotPickedIsDisabledAtTheLimit() {
        #expect(!isSelectable(uuid: carol, selectedUuids: [ada, bob], maxMembers: 3))
    }

    /// So that the selection can still shrink once full.
    @Test func aRowPickedStaysEnabledAtTheLimit() {
        #expect(isSelectable(uuid: bob, selectedUuids: [ada, bob], maxMembers: 3))
    }

    @Test func ninetyNineOthersFillAGroupOfAHundred() {
        let ninetyEight = Set((1...98).map { "member-\($0)" })

        let ninetyNine = toggledSelection(selected: ninetyEight, uuid: "member-99", maxMembers: 100)

        #expect(ninetyNine.count == 99)
        #expect(memberCount(selectedUuids: ninetyNine) == 100)
        #expect(toggledSelection(selected: ninetyNine, uuid: "member-100", maxMembers: 100) == ninetyNine)
    }
}

/// Picking members to add to a group that exists: whom the picker lists, see `invitableUsers`, and
/// how far it lets the user go, the members the group has counting against the limit, and whoever
/// it picked and no longer lists counting for nothing, see `listedPicks` and
/// `toggledListedSelection`, as Android's `MemberAdditionTest` says it.
struct MemberAdditionTests {
    private let alice = Person(id: "alice", fullName: "Alice")
    private let bobby = Person(id: "bob", fullName: "Bob")
    private let carole = Person(id: "carol", fullName: "Carol")

    @Test func thePickerListsTheContactsNotInTheGroupYet() {
        #expect(invitableUsers(users: [alice, bobby, carole], memberUuids: ["self", "alice", "carol"]) == [bobby])
    }

    @Test func thePickerKeepsTheOrderTheContactsComeIn() {
        #expect(invitableUsers(users: [carole, alice], memberUuids: ["self"]) == [carole, alice])
    }

    /// Rather than that a search found nobody, with nothing searched.
    @Test func thePickerTellsAUserWhoseContactsAreAllInTheGroupSo() {
        #expect(nobodyToPick(contacts: [alice, bobby], memberUuids: ["self", "alice", "bob"]) == .allMembers)
    }

    @Test func thePickerTellsAUserWithNoContactSo() {
        #expect(nobodyToPick(contacts: [], memberUuids: ["self"]) == .noContacts)
    }

    /// A group to create has no members yet, the user aside, who is never among their contacts.
    @Test func thePickerTellsAUserCreatingAGroupWithNoContactSo() {
        #expect(nobodyToPick(contacts: [], memberUuids: []) == .noContacts)
    }

    /// An empty list then means a search that found nobody.
    @Test func thePickerGivesNoReasonWhileAContactCanBePicked() {
        #expect(nobodyToPick(contacts: [alice, bobby], memberUuids: ["self", "alice"]) == nil)
        #expect(nobodyToPick(contacts: [alice], memberUuids: []) == nil)
    }

    @Test func theCounterCountsTheMembersOfTheGroupWithNobodyPicked() {
        #expect(memberCount(selectedUuids: [], currentMemberCount: 3) == 3)
    }

    @Test func theCounterCountsWhoeverIsPickedOnTopOfTheMembers() {
        #expect(memberCount(selectedUuids: ["bob", "dave"], currentMemberCount: 3) == 5)
    }

    @Test func aRowNotPickedIsEnabledWhileTheGroupHasRoom() {
        #expect(isSelectable(uuid: "bob", selectedUuids: ["dave"], maxMembers: 100, currentMemberCount: 98))
    }

    /// Ninety eight members and two picked make a hundred.
    @Test func aRowNotPickedIsDisabledOnceTheMembersAndThosePickedFillTheGroup() {
        #expect(!isSelectable(uuid: "bob", selectedUuids: ["dave", "erin"], maxMembers: 100, currentMemberCount: 98))
    }

    @Test func aRowPickedStaysEnabledOnceTheGroupIsFull() {
        #expect(isSelectable(uuid: "dave", selectedUuids: ["dave", "erin"], maxMembers: 100, currentMemberCount: 98))
    }

    @Test func nobodyCanBePickedForAGroupAlreadyFull() {
        #expect(!isSelectable(uuid: "bob", selectedUuids: [], maxMembers: 100, currentMemberCount: 100))
        #expect(toggledSelection(selected: [], uuid: "bob", maxMembers: 100, currentMemberCount: 100).isEmpty)
    }

    @Test func pickingStopsAtTheLastPlaceTheMembersLeave() {
        let one = toggledSelection(selected: [], uuid: "bob", maxMembers: 100, currentMemberCount: 99)

        #expect(one == ["bob"])
        #expect(toggledSelection(selected: one, uuid: "dave", maxMembers: 100, currentMemberCount: 99) == one)
    }

    @Test func unpickingSomeoneInAFullGroupTakesThemOut() {
        #expect(toggledSelection(selected: ["dave", "erin"], uuid: "erin", maxMembers: 100, currentMemberCount: 98) == ["dave"])
    }

    /// A contact a sync forgot while the picker was open, who could not be unpicked there.
    @Test func someonePickedThePickerNoLongerListsIsNoLongerPicked() {
        #expect(listedPicks(picked: ["bob", "dave"], listedUuids: ["bob", "carol"]) == ["bob"])
    }

    @Test func someonePickedThePickerNoLongerListsLeavesTheirPlaceToSomebodyElse() {
        let picked = toggledListedSelection(picked: ["dave"], listedUuids: ["bob"], uuid: "bob", maxMembers: 100, currentMemberCount: 99)

        #expect(listedPicks(picked: picked, listedUuids: ["bob"]) == ["bob"])
    }

    /// As when the list is read again, and emits nobody first.
    @Test func someonePickedThePickerDoesNotListYetIsPickedAgainOnceListed() {
        let picked = toggledListedSelection(picked: ["dave"], listedUuids: [], uuid: "bob", maxMembers: 100, currentMemberCount: 3)

        #expect(listedPicks(picked: picked, listedUuids: ["bob", "dave"]) == ["bob", "dave"])
    }

    @Test func unpickingSomeoneThePickerListsLeavesWhoeverItDoesNotListPicked() {
        let picked = toggledListedSelection(picked: ["bob", "dave"], listedUuids: ["bob"], uuid: "bob", maxMembers: 100, currentMemberCount: 3)

        #expect(picked == ["dave"])
    }
}

/// Who may add and remove members of a group from its settings, see `memberActions`, and whom, see
/// `isRemovable`: its creator alone, from a device that holds the group, as Android's
/// `MemberActionsTest` says it.
struct MemberActionsTests {
    private func group(canChangeMembers: Bool, hasPendingChange: Bool = false) -> GroupDetails {
        groupDetails(
            hasPendingChange: hasPendingChange,
            canChangeMembers: canChangeMembers,
            canEdit: canChangeMembers,
            canLeave: !canChangeMembers,
            canDelete: canChangeMembers
        )
    }

    @Test func theCreatorMayChangeTheMembers() {
        #expect(group(canChangeMembers: true).memberActions == .available)
    }

    @Test func aMemberWhoDidNotCreateTheGroupMayNotChangeItsMembers() {
        #expect(group(canChangeMembers: false).memberActions == .none)
    }

    @Test func theCreatorWaitsForAChangeOnItsWayToTheServer() {
        #expect(group(canChangeMembers: true, hasPendingChange: true).memberActions == .waiting)
    }

    /// A creation on its way counts as a change, see `GroupDetails.hasPendingChange`.
    @Test func aMemberWhoDidNotCreateTheGroupIsOfferedNothingWhateverIsPending() {
        #expect(group(canChangeMembers: false, hasPendingChange: true).memberActions == .none)
    }

    @Test func theCreatorMayRemoveAnotherMember() {
        #expect(isRemovable(userUuid: "bob", selfUuid: "alice", actions: .available))
    }

    @Test func theCreatorMayNotRemoveThemself() {
        #expect(!isRemovable(userUuid: "alice", selfUuid: "alice", actions: .available))
    }

    @Test func nobodyIsRemovedWhileAChangeIsOnItsWay() {
        #expect(!isRemovable(userUuid: "bob", selfUuid: "alice", actions: .waiting))
    }

    @Test func aMemberWhoDidNotCreateTheGroupMayRemoveNobody() {
        #expect(!isRemovable(userUuid: "bob", selfUuid: "carol", actions: .none))
    }
}

/// Whom the settings of a group offer to leave it, see `isLeavable`, to take it off this device,
/// and its history, as Android's `LeaveActionTest` and `HistoryActionTest` say it.
struct GroupActionsTests {
    private func group(canChangeMembers: Bool, canLeave: Bool, isLeft: Bool = false) -> GroupDetails {
        groupDetails(
            isLeft: isLeft,
            canChangeMembers: canChangeMembers,
            canEdit: canChangeMembers,
            canVouchForMembers: !isLeft,
            canLeave: canLeave,
            canDelete: canChangeMembers && !isLeft
        )
    }

    /// Changing its members is theirs alone, and nobody else could.
    @Test func theCreatorIsOfferedTheMembersAndNotTheLeave() {
        let group = group(canChangeMembers: true, canLeave: false)

        #expect(!group.isLeavable)
        #expect(group.memberActions == .available)
    }

    @Test func aMemberWhoDidNotCreateTheGroupIsOfferedTheLeaveAndNotTheMembers() {
        let group = group(canChangeMembers: false, canLeave: true)

        #expect(group.isLeavable)
        #expect(group.memberActions == .none)
    }

    /// It stays read-only, with nothing left to do about it.
    @Test func aGroupTheUserIsNoLongerInOffersNeither() {
        let group = group(canChangeMembers: false, canLeave: false, isLeft: true)

        #expect(!group.isLeavable)
        #expect(group.memberActions == .none)
    }

    /// Nothing but a log-out would take its copy away otherwise.
    @Test func aGroupTheUserIsNoLongerInCanBeTakenOffThisDevice() {
        #expect(group(canChangeMembers: false, canLeave: false, isLeft: true).isRemovableFromDevice)
    }

    @Test func aGroupTheUserIsStillInCannotBeTakenOffThisDeviceAlone() {
        #expect(!group(canChangeMembers: false, canLeave: true).isRemovableFromDevice)
        #expect(!group(canChangeMembers: true, canLeave: false).isRemovableFromDevice)
    }

    @Test func theCreatorIsOfferedTheHistory() {
        #expect(group(canChangeMembers: true, canLeave: false).isHistoryReadable)
    }

    @Test func aMemberWhoDidNotCreateTheGroupIsOfferedTheHistory() {
        #expect(group(canChangeMembers: false, canLeave: true).isHistoryReadable)
    }

    /// It stays read-only, with what the user had of it.
    @Test func aGroupTheUserIsNoLongerInOffersNoHistory() {
        #expect(!group(canChangeMembers: false, canLeave: false, isLeft: true).isHistoryReadable)
    }
}

/// What the sheet of a member of the group offers, see `memberSheetActions`, and where it tells the
/// user stands with them once a request sent from it is out, see `isRequestOut` and
/// `shownRelation`, as Android's `MemberSheetTest` says it.
struct MemberSheetTests {
    private let relations: [MemberRelation] = [.contact, .requestReceived, .requestSent, .unrelated]

    private let errors: [MemberContactRequestError] = [
        .notFound, .alreadyContact, .alreadyPending, .rateLimited, .identityChanged, .notInGroup, .blocked,
        .noInternet, .timeout, .serverUnreachable, .unknown,
    ]

    @Test func theSheetOfAContactOffersTheirProfile() {
        #expect(memberSheetActions(relation: .contact, canVouchForMembers: true, isRemovable: false) == [.viewProfile])
    }

    @Test func theSheetOfAMemberWhoAskedTheUserOffersToAnswerThem() {
        #expect(memberSheetActions(relation: .requestReceived, canVouchForMembers: true, isRemovable: false) == [.answerRequest])
    }

    @Test func theSheetOfAMemberTheUserAskedTellsTheRequestIsSent() {
        #expect(memberSheetActions(relation: .requestSent, canVouchForMembers: true, isRemovable: false) == [.requestSent])
    }

    @Test func theSheetOfAnybodyElseOffersToSendThemARequest() {
        #expect(memberSheetActions(relation: .unrelated, canVouchForMembers: true, isRemovable: false) == [.sendRequest])
    }

    /// One the user was removed from, brought back after a log-out, or whose creator's identity is
    /// in doubt: nothing else on this device vouches for who they are.
    @Test func noRequestIsOfferedFromAGroupThatDoesNotVouchForItsMembers() {
        #expect(memberSheetActions(relation: .unrelated, canVouchForMembers: false, isRemovable: false).isEmpty)
    }

    @Test func aGroupThatDoesNotVouchForItsMembersStillOffersWhatNeedsNoGroup() {
        #expect(memberSheetActions(relation: .contact, canVouchForMembers: false, isRemovable: false) == [.viewProfile])
        #expect(memberSheetActions(relation: .requestReceived, canVouchForMembers: false, isRemovable: false) == [.answerRequest])
        #expect(memberSheetActions(relation: .requestSent, canVouchForMembers: false, isRemovable: false) == [.requestSent])
    }

    @Test func theRemovalComesAfterWhatTheRelationCallsFor() {
        for relation in relations {
            let actions = memberSheetActions(relation: relation, canVouchForMembers: true, isRemovable: true)

            #expect(actions.count == 2)
            #expect(actions.last == .remove)
        }
    }

    @Test func aMemberWhoCannotBeRemovedIsOfferedNoRemoval() {
        for relation in relations {
            #expect(!memberSheetActions(relation: relation, canVouchForMembers: true, isRemovable: false).contains(.remove))
        }
    }

    /// The database tells the request stored a moment after it returns.
    @Test func aRequestTheServerTookIsToldAsSentBeforeTheDatabaseTellsIt() {
        #expect(shownRelation(relation: .unrelated, isRequestTaken: true) == .requestSent)
    }

    @Test func whereTheUserStandsWithAMemberIsToldAsReadWithNoRequestTaken() {
        for relation in relations {
            #expect(shownRelation(relation: relation, isRequestTaken: false) == relation)
        }
    }

    /// They accepted it already, say, or had asked the user meanwhile.
    @Test func aRequestTakenNeverHidesWhatTheDatabaseTellsSince() {
        for relation in [MemberRelation.contact, .requestReceived, .requestSent] {
            #expect(shownRelation(relation: relation, isRequestTaken: true) == relation)
        }
    }

    @Test func aRequestTheServerTookIsOut() {
        #expect(isRequestOut(error: nil))
    }

    /// Offered again, it would spend another of their key packages for the same refusal.
    @Test func aRequestRefusedAsAlreadyPendingIsOut() {
        #expect(isRequestOut(error: .alreadyPending))
    }

    @Test func aRequestThatFailedOtherwiseIsNotOut() {
        for error in errors where error != .alreadyPending {
            #expect(!isRequestOut(error: error))
        }
    }
}

/// Whose each debt of a group is for the user, see `Debt.side(for:)`: the settle-up tab shows every
/// debt in the group, and words each one for the user reading it, as Android's `DebtSideTest`
/// says it.
struct DebtSideTests {
    private let bobOwesAlice = Debt(debtorUuid: "bob", creditorUuid: "alice", amount: "10")

    /// Theirs to record paid back, which only the creditor can.
    @Test func aDebtOwedToTheUserIsOwedToThem() {
        #expect(bobOwesAlice.side(for: "alice") == .owedToUser)
    }

    @Test func aDebtTheUserOwesIsOwedByThem() {
        #expect(bobOwesAlice.side(for: "bob") == .owedByUser)
    }

    /// Neither "you owe" nor "owes you": somebody else owes somebody else.
    @Test func aDebtBetweenTwoOtherMembersIsBetweenOthers() {
        #expect(bobOwesAlice.side(for: "carol") == .betweenOthers)
        #expect(bobOwesAlice.side(for: nil) == .betweenOthers)
    }
}
