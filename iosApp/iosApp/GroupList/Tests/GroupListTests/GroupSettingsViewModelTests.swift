import Foundation
import Testing

@testable import GroupList

/// The settings of the group `groupDetails` makes, once read, with its members' names.
@MainActor
private func settings(_ callBacks: FakeGroupCallBacks) async -> GroupSettingsViewModel {
    let viewModel = GroupSettingsViewModel(groupId: groupId, callBacks: callBacks)

    await settle { viewModel.group != nil && !viewModel.names.read.isEmpty }

    return viewModel
}

/// How the settings of a group name its members, and the open sheet the member it is about: as
/// names are read, a member the user is no contact of being named by a group sync once the screen
/// is open, as Android's `MemberNamesTest` says it.
@MainActor
struct MemberNamesTests {
    private let aliceAndBob = [GroupMember(id: "alice", isOwner: true), GroupMember(id: "bob", isOwner: false)]

    @Test func theMembersAreListedUnderTheNamesThisDeviceHolds() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.shownGroup.send(groupDetails(members: aliceAndBob, canChangeMembers: false, canEdit: false, canLeave: true, canDelete: false))
        callBacks.names.send(["alice": "Alice", "bob": "Bob"])
        let viewModel = await settings(callBacks)

        #expect(viewModel.members.map { viewModel.names.displayName(of: $0.id) } == ["Alice", "Bob"])
    }

    @Test func aNameReadAfterTheScreenOpenedShowsInTheMembersList() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.shownGroup.send(groupDetails(members: aliceAndBob))
        callBacks.names.send(["alice": "Alice"])
        let viewModel = await settings(callBacks)

        #expect(viewModel.members.map { viewModel.names.name(of: $0.id) } == ["Alice", nil])
        #expect(viewModel.names.displayName(of: "bob") == MemberNames.someoneElse)

        callBacks.names.send(["alice": "Alice", "bob": "Bob"])
        await settle { viewModel.names.name(of: "bob") != nil }

        #expect(viewModel.members.map { viewModel.names.name(of: $0.id) } == ["Alice", "Bob"])
    }

    /// The group lists them as they joined; the user comes first, the others by name.
    @Test func theMembersAreListedTheUserFirstThenByNameAndThoseWithNoneLast() async {
        let callBacks = FakeGroupCallBacks()
        let members = ["zoe", "unknown", "emile", me, "bob"].map { GroupMember(id: $0, isOwner: $0 == "zoe") }
        callBacks.shownGroup.send(groupDetails(members: members))
        callBacks.names.send(["zoe": "Zoé", "emile": "Émile", "bob": "bob", me: "Me"])
        let viewModel = await settings(callBacks)

        #expect(viewModel.members.map(\.id) == [me, "bob", "emile", "zoe", "unknown"])
    }

    @Test func theOpenSheetShowsANameReadAfterItOpened() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.shownGroup.send(groupDetails(members: aliceAndBob))
        callBacks.names.send(["alice": "Alice"])
        let viewModel = await settings(callBacks)

        viewModel.openMember(userUuid: "bob")
        #expect(viewModel.names.name(of: "bob") == nil)

        callBacks.names.send(["alice": "Alice", "bob": "Bob"])
        await settle { viewModel.names.name(of: "bob") != nil }

        #expect(viewModel.names.displayName(of: "bob") == "Bob")
    }

    /// Listed before, they would show with no name, then jump to their place once named.
    @Test func theGroupShowsOnceItsMembersNamesAreRead() async {
        let callBacks = FakeGroupCallBacks()
        let gate = Gate()
        callBacks.namesGate = gate
        let viewModel = GroupSettingsViewModel(groupId: groupId, callBacks: callBacks)

        await settle()

        #expect(viewModel.group == nil)
        #expect(viewModel.members.isEmpty)

        gate.open()
        await settle { viewModel.group != nil }

        #expect(viewModel.members.map { viewModel.names.displayName(of: $0.id) } == ["Me", "Ada"])
    }

    /// The group shows as it was meanwhile.
    @Test func aMemberAddedShowsOnceNamed() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = await settings(callBacks)
        let gate = Gate()

        callBacks.namesGate = gate
        callBacks.shownGroup.send(
            groupDetails(members: [GroupMember(id: me, isOwner: true), GroupMember(id: ada, isOwner: false), GroupMember(id: bob, isOwner: false)])
        )
        await settle()

        #expect(viewModel.members.map(\.id) == [me, ada])
        #expect(viewModel.picker.currentMemberCount == 2)

        gate.open()
        await settle { viewModel.members.count == 3 }

        #expect(viewModel.members.map { viewModel.names.displayName(of: $0.id) } == ["Me", "Ada", "Bob"])
        #expect(viewModel.picker.currentMemberCount == 3)
        #expect(viewModel.picker.memberUuids == [me, ada, bob])
    }

    /// A sync read their removal with the sheet open.
    @Test func theSheetOfAMemberTheGroupListsNoMoreStillNamesThem() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.shownGroup.send(groupDetails(members: aliceAndBob))
        callBacks.names.send(["alice": "Alice", "bob": "Bob"])
        let viewModel = await settings(callBacks)

        viewModel.openMember(userUuid: "bob")
        callBacks.shownGroup.send(groupDetails(members: [aliceAndBob[0]]))
        await settle { viewModel.members.count == 1 }
        await settle()

        #expect(viewModel.openedMemberUuid == "bob")
        #expect(viewModel.names.displayName(of: "bob") == "Bob")
    }
}

/// Adding members from a group's settings: the picker the group's members are left out of, which
/// closes once the server took them, or will, and stays open on a failure, as Android's
/// `GroupSettingsViewModel` does.
@MainActor
struct MemberAdditionFlowTests {
    @Test func thePickerListsTheContactsNotInTheGroupByNameAndCountsItsMembers() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.contacts.send([Person(id: carol, fullName: "Carol"), Person(id: ada, fullName: "Ada"), Person(id: bob, fullName: "Bob")])
        let viewModel = await settings(callBacks)

        viewModel.beginAddingMembers()
        await settle { viewModel.picker.found != nil && viewModel.picker.contacts != nil }

        #expect(viewModel.isPickingMembers)
        #expect(viewModel.picker.users.map(\.id) == [bob, carol])
        #expect(viewModel.picker.memberCount == 2)
        #expect(viewModel.picker.nobodyToPick == nil)
    }

    @Test func thePickerTellsAUserWhoseContactsAreAllInTheGroupSo() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.contacts.send([Person(id: ada, fullName: "Ada")])
        let viewModel = await settings(callBacks)

        viewModel.beginAddingMembers()
        await settle { viewModel.picker.contacts != nil }

        #expect(viewModel.picker.nobodyToPick == .allMembers)
    }

    @Test func aSearchFindsContactsByName() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = await settings(callBacks)

        viewModel.beginAddingMembers()
        viewModel.picker.search("car")
        await settle { viewModel.picker.found?.count == 1 }

        #expect(viewModel.picker.users.map(\.id) == [carol])
        #expect(callBacks.searches.contains("car"))
    }

    /// Members are added one at least.
    @Test func thePickerCannotBeConfirmedWithNobodyPicked() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = await settings(callBacks)

        viewModel.beginAddingMembers()
        await settle { viewModel.picker.contacts != nil }

        #expect(!viewModel.canAddMembers)
        #expect(!viewModel.picker.canConfirm)

        viewModel.addMembers()
        await settle()

        #expect(callBacks.added.isEmpty)
    }

    /// As its Confirm button reads it, from the picker it follows, as the user picks.
    @Test func thePickerCanBeConfirmedOnceSomebodyIsPickedAndNoLongerOnceNobodyIs() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = await settings(callBacks)

        viewModel.beginAddingMembers()
        await settle { viewModel.picker.contacts != nil }
        viewModel.picker.toggle(bob)

        #expect(viewModel.picker.canConfirm)
        #expect(viewModel.canAddMembers)

        viewModel.picker.toggle(bob)

        #expect(!viewModel.picker.canConfirm)
        #expect(!viewModel.canAddMembers)
    }

    /// The picker stays open, for the user to pick differently, and nobody picked is nothing to add.
    @Test func everyPickRemovedAfterAFailureLeavesNothingToConfirm() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.addition = .failed(.failure(.noInternet))
        let viewModel = await settings(callBacks)

        viewModel.beginAddingMembers()
        await settle { viewModel.picker.contacts != nil }
        viewModel.picker.toggle(bob)
        viewModel.addMembers()
        await settle { viewModel.additionError != nil }
        viewModel.picker.toggle(bob)

        #expect(!viewModel.picker.canConfirm)

        viewModel.addMembers()
        await settle()

        #expect(callBacks.added == [[bob]])
    }

    @Test func membersAddedCloseThePickerAndTellNothing() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = await settings(callBacks)

        viewModel.beginAddingMembers()
        await settle { viewModel.picker.contacts != nil }
        viewModel.picker.toggle(carol)
        viewModel.picker.toggle(bob)
        viewModel.addMembers()
        await settle { !viewModel.isPickingMembers }

        // In the order the picker lists them.
        #expect(callBacks.added == [[bob, carol]])
        #expect(!viewModel.isPickingMembers)
        #expect(viewModel.notice == nil)
    }

    /// Emptied as it closes, it would show nobody as it slides away.
    @Test func thePickerKeepsShowingWhatItPickedUntilItsSheetIsGone() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = await settings(callBacks)

        viewModel.beginAddingMembers()
        await settle { viewModel.picker.contacts != nil }
        viewModel.picker.toggle(bob)
        viewModel.addMembers()
        await settle { !viewModel.isPickingMembers }

        #expect(viewModel.picker.picked == [bob])
        #expect(viewModel.picker.contacts != nil)

        viewModel.endAddingMembers()

        #expect(viewModel.picker.picked.isEmpty)
        #expect(viewModel.picker.contacts == nil)
    }

    @Test func membersToBeAddedOnceTheServerCanCloseThePickerAndSaySo() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.addition = .pending
        let viewModel = await settings(callBacks)

        viewModel.beginAddingMembers()
        await settle { viewModel.picker.contacts != nil }
        viewModel.picker.toggle(bob)
        viewModel.addMembers()
        await settle { !viewModel.isPickingMembers }

        #expect(viewModel.notice == .pending)
    }

    /// For the user to pick differently.
    @Test func membersThatCouldNotBeAddedLeaveThePickerOpenWithWhy() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.addition = .failed(.members(.deleted, names: ["Bob"]))
        let viewModel = await settings(callBacks)

        viewModel.beginAddingMembers()
        await settle { viewModel.picker.contacts != nil }
        viewModel.picker.toggle(bob)
        viewModel.addMembers()
        await settle { viewModel.additionError != nil }

        #expect(viewModel.isPickingMembers)
        #expect(viewModel.additionError == .members(.deleted, names: ["Bob"]))
        #expect(viewModel.picker.selectedUuids == [bob])
        #expect(viewModel.notice == nil)
    }

    /// What comes of it is told there.
    @Test func thePickerCannotBeClosedWhileMembersAreBeingAdded() async {
        let callBacks = FakeGroupCallBacks()
        let gate = Gate()
        let viewModel = await settings(callBacks)

        viewModel.beginAddingMembers()
        await settle { viewModel.picker.contacts != nil }
        viewModel.picker.toggle(bob)
        callBacks.gate = gate
        viewModel.addMembers()
        viewModel.cancelAddingMembers()

        #expect(viewModel.isPickingMembers)
        #expect(viewModel.isChanging)

        gate.open()
        await settle { !viewModel.isPickingMembers }

        #expect(!viewModel.isChanging)
    }

    /// A contact a sync forgot while the picker was open, who could not be unpicked there.
    @Test func someonePickedWhoIsNoLongerListedIsNotAdded() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = await settings(callBacks)

        viewModel.beginAddingMembers()
        await settle { viewModel.picker.contacts != nil }
        viewModel.picker.toggle(bob)
        viewModel.picker.toggle(carol)
        callBacks.contacts.send([Person(id: ada, fullName: "Ada"), Person(id: carol, fullName: "Carol")])
        await settle { viewModel.picker.contacts?.count == 2 }
        viewModel.addMembers()
        await settle { !viewModel.isPickingMembers }

        #expect(callBacks.added == [[carol]])
    }

    @Test func nobodyIsAddedToAGroupWhoseLastChangeIsStillOnItsWay() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.shownGroup.send(groupDetails(hasPendingChange: true))
        let viewModel = await settings(callBacks)

        viewModel.beginAddingMembers()

        #expect(viewModel.memberActions == .waiting)
        #expect(!viewModel.isPickingMembers)
    }
}

/// Removing a member, leaving the group, deleting it, and taking it off this device, one change at
/// a time, as Android's `GroupSettingsViewModel` makes them.
@MainActor
struct GroupChangeTests {
    @Test func aMemberRemovedTellsNothing() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = await settings(callBacks)

        viewModel.remove(userUuid: ada)
        #expect(viewModel.removingUuid == ada)
        await settle { !viewModel.isChanging }

        #expect(callBacks.removed == [ada])
        #expect(viewModel.removingUuid == nil)
        #expect(viewModel.notice == nil)
    }

    @Test func aRemovalToBeSentOnceTheServerCanSaysSo() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.removal = .pending
        let viewModel = await settings(callBacks)

        viewModel.remove(userUuid: ada)
        await settle { !viewModel.isChanging }

        #expect(viewModel.notice == .pending)
    }

    @Test func aRemovalThatFailedTellsWhy() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.removal = .failed(.failure(.noInternet))
        let viewModel = await settings(callBacks)

        viewModel.remove(userUuid: ada)
        await settle { !viewModel.isChanging }

        #expect(viewModel.notice == .failed(.failure(.noInternet)))
    }

    /// The creator, who never leaves it, as nobody else could change its members.
    @Test func theUserIsNeverRemoved() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = await settings(callBacks)

        viewModel.remove(userUuid: me)
        await settle()

        #expect(callBacks.removed.isEmpty)
    }

    @Test func oneChangeIsMadeAtATime() async {
        let callBacks = FakeGroupCallBacks()
        let gate = Gate()
        callBacks.gate = gate
        let viewModel = await settings(callBacks)

        viewModel.remove(userUuid: ada)
        viewModel.delete()
        viewModel.remove(userUuid: ada)
        gate.open()
        await settle { !viewModel.isChanging }

        #expect(callBacks.removed == [ada])
        #expect(callBacks.deleted.isEmpty)
    }

    /// The group, read again, is left, and the screen shows it read-only.
    @Test func aLeaveThatWentThroughTellsNothing() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.shownGroup.send(groupDetails(canChangeMembers: false, canEdit: false, canLeave: true, canDelete: false))
        let viewModel = await settings(callBacks)

        viewModel.leave()
        #expect(viewModel.change == .leave)
        await settle { !viewModel.isChanging }

        #expect(callBacks.left == [groupId])
        #expect(viewModel.change == nil)
        #expect(viewModel.notice == nil)
    }

    @Test func aLeaveThatFailedTellsWhy() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.leaveFailure = .unknown
        callBacks.shownGroup.send(groupDetails(canChangeMembers: false, canEdit: false, canLeave: true, canDelete: false))
        let viewModel = await settings(callBacks)

        viewModel.leave()
        await settle { !viewModel.isChanging }

        #expect(viewModel.notice == .failed(.failure(.unknown)))
    }

    /// Its owner deletes it instead.
    @Test func theOwnerCannotLeave() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = await settings(callBacks)

        viewModel.leave()
        await settle()

        #expect(callBacks.left.isEmpty)
    }

    @Test func aDeletionThatFailedTellsWhy() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.deleteFailure = .serverUnreachable
        let viewModel = await settings(callBacks)

        viewModel.delete()
        await settle { !viewModel.isChanging }

        #expect(callBacks.deleted == [groupId])
        #expect(viewModel.notice == .failed(.failure(.serverUnreachable)))
    }

    /// Nothing changes before the server answers, and a change it has yet to take would bring the
    /// group back.
    @Test func aGroupWhoseLastChangeIsStillOnItsWayIsNotDeleted() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.shownGroup.send(groupDetails(hasPendingChange: true))
        let viewModel = await settings(callBacks)

        viewModel.delete()
        await settle()

        #expect(callBacks.deleted.isEmpty)
    }

    /// Its screens close as it goes: the settings keep showing it meanwhile.
    @Test func aGroupDeletedKeepsShowingUntilItsScreensClose() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = await settings(callBacks)

        viewModel.delete()
        await settle { !viewModel.isChanging }
        callBacks.shownGroup.send(nil)
        await settle()

        #expect(viewModel.group?.id == groupId)
        #expect(viewModel.notice == nil)
    }

    @Test func aGroupTheUserIsNoLongerInIsTakenOffThisDevice() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.shownGroup.send(groupDetails(isLeft: true, canChangeMembers: false, canEdit: false, canVouchForMembers: false, canDelete: false))
        let viewModel = await settings(callBacks)

        viewModel.forget()
        await settle { !viewModel.isChanging }

        #expect(callBacks.forgotten == [groupId])
        #expect(callBacks.deleted.isEmpty)
    }

    @Test func aGroupTheUserIsStillInIsNotTakenOffThisDeviceAlone() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = await settings(callBacks)

        viewModel.forget()
        await settle()

        #expect(callBacks.forgotten.isEmpty)
    }
}

/// What the sheet of a member offers as where the user stands with them is read, and a contact
/// request sent from it, as Android's `GroupSettingsViewModel.memberSheet` makes it.
@MainActor
struct MemberSheetStateTests {
    @Test func theSheetOffersNothingUntilWhereTheUserStandsWithTheMemberIsRead() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = await settings(callBacks)

        viewModel.openMember(userUuid: ada)

        #expect(viewModel.memberSheetActions == nil)

        await settle { viewModel.relation != nil }

        #expect(viewModel.memberSheetActions == [.sendRequest, .remove])
    }

    /// Their sheet would offer nothing.
    @Test func theUsersOwnSheetDoesNotOpen() async {
        let viewModel = await settings(FakeGroupCallBacks())

        viewModel.openMember(userUuid: me)

        #expect(viewModel.openedMemberUuid == nil)
    }

    @Test func aMemberWhoIsNotTheUsersToRemoveIsOfferedWhatTheirRelationCallsForAlone() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.shownGroup.send(groupDetails(canChangeMembers: false, canEdit: false, canLeave: true, canDelete: false))
        callBacks.relationToMember.send(.contact)
        let viewModel = await settings(callBacks)

        viewModel.openMember(userUuid: ada)
        await settle { viewModel.relation != nil }

        #expect(viewModel.memberSheetActions == [.viewProfile])
    }

    /// The database tells the request stored a moment after it returns.
    @Test func aRequestTheServerTookIsToldAsSentAtOnce() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = await settings(callBacks)

        viewModel.openMember(userUuid: ada)
        await settle { viewModel.relation != nil }
        viewModel.sendContactRequest()
        await settle { !viewModel.isSendingRequest && viewModel.isRequestTaken }

        #expect(callBacks.requestsSent == [ada])
        #expect(viewModel.memberSheetActions == [.requestSent, .remove])
        #expect(viewModel.requestError == nil)
    }

    /// Offered again, it would spend another of their key packages for the same refusal.
    @Test func aRequestRefusedAsAlreadyPendingIsToldAsSentWithWhy() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.requestFailure = .alreadyPending
        let viewModel = await settings(callBacks)

        viewModel.openMember(userUuid: ada)
        await settle { viewModel.relation != nil }
        viewModel.sendContactRequest()
        await settle { viewModel.requestError != nil }

        #expect(viewModel.memberSheetActions?.first == .requestSent)
        #expect(viewModel.requestError == .alreadyPending)
    }

    @Test func aRequestThatFailedOtherwiseCanBeSentAgain() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.requestFailure = .noInternet
        let viewModel = await settings(callBacks)

        viewModel.openMember(userUuid: ada)
        await settle { viewModel.relation != nil }
        viewModel.sendContactRequest()
        await settle { viewModel.requestError != nil }

        #expect(viewModel.memberSheetActions?.first == .sendRequest)
        #expect(viewModel.requestError == .noInternet)
    }

    /// What comes of it is told there.
    @Test func theSheetCannotBeClosedWhileARequestIsOnItsWay() async {
        let callBacks = FakeGroupCallBacks()
        let gate = Gate()
        callBacks.gate = gate
        let viewModel = await settings(callBacks)

        viewModel.openMember(userUuid: ada)
        await settle { viewModel.relation != nil }
        viewModel.sendContactRequest()
        viewModel.sendContactRequest()
        viewModel.closeMember()

        #expect(viewModel.openedMemberUuid == ada)
        #expect(viewModel.isSendingRequest)

        gate.open()
        await settle { !viewModel.isSendingRequest }
        viewModel.closeMember()

        #expect(viewModel.openedMemberUuid == nil)
        #expect(callBacks.requestsSent == [ada])
    }

    /// They accept it and then remove the user, say: whatever the read tells since shows as read.
    @Test func aRequestTakenHidesNothingTheDatabaseTellsSince() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = await settings(callBacks)

        viewModel.openMember(userUuid: ada)
        await settle { viewModel.relation != nil }
        viewModel.sendContactRequest()
        await settle { viewModel.isRequestTaken }

        callBacks.relationToMember.send(.contact)
        await settle { viewModel.relation == .contact }
        callBacks.relationToMember.send(.unrelated)
        await settle { viewModel.relation == .unrelated }

        #expect(!viewModel.isRequestTaken)
        #expect(viewModel.memberSheetActions?.first == .sendRequest)
    }

    @Test func whatTheSheetToldGoesWithIt() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.requestFailure = .blocked
        let viewModel = await settings(callBacks)

        viewModel.openMember(userUuid: ada)
        await settle { viewModel.relation != nil }
        viewModel.sendContactRequest()
        await settle { viewModel.requestError != nil }
        viewModel.closeMember()

        #expect(viewModel.requestError == nil)
        #expect(viewModel.relation == nil)

        viewModel.openMember(userUuid: ada)
        #expect(viewModel.requestError == nil)
    }
}
