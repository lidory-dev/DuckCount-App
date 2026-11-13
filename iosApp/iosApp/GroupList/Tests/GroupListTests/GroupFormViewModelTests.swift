import Foundation
import Testing

@testable import GroupList

/// When the form of a group being edited can be confirmed, see `GroupFormViewModel`: once what it
/// holds is other than what the group shows, read again each time that changes, whatever the user
/// typed meanwhile, which the form keeps, as Android's `GroupEditFormTest` says it.
@MainActor
struct GroupEditFormTests {
    private func form(_ callBacks: FakeGroupCallBacks = FakeGroupCallBacks()) async -> GroupFormViewModel {
        let viewModel = GroupFormViewModel(callBacks: callBacks, mode: .edit(groupId: groupId))

        await settle { !viewModel.isBeingRead }

        return viewModel
    }

    @Test func theFormOpensWithWhatTheGroupShowsAndCannotBeConfirmedAsItIs() async {
        let viewModel = await form()

        #expect(viewModel.form.title == "Ski trip")
        #expect(viewModel.form.description == "Chalet and lift passes")
        #expect(!viewModel.canSave)
        #expect(!viewModel.hasEdits)
    }

    @Test func aChangeTypedCanBeConfirmedUntilItIsTypedBack() async {
        let viewModel = await form()

        viewModel.updateTitle("Ski trip 2027")
        #expect(viewModel.canSave)

        viewModel.updateTitle("Ski trip")
        #expect(!viewModel.canSave)

        viewModel.updateDescription("")
        #expect(viewModel.canSave)
    }

    /// Held as typed, for the field, and saved without it, which would change nothing.
    @Test func aSpaceTypedAfterTheTitleIsHeldAndChangesNothing() async {
        let viewModel = await form()

        viewModel.updateTitle("Ski trip ")

        #expect(viewModel.form.title == "Ski trip ")
        #expect(!viewModel.canSave)
    }

    /// As an edit refused as outdated shows the version the server holds, or a sync an earlier edit
    /// of the user's whose answer was lost.
    @Test func whatTheGroupShowsSinceIsWhatTheFormIsComparedWith() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = await form(callBacks)

        viewModel.updateTitle("Ski trip 2027")

        callBacks.shownGroup.send(groupDetails(title: "Ski trip 2027"))
        await settle { viewModel.form.shown?.title == "Ski trip 2027" }

        #expect(viewModel.form.title == "Ski trip 2027")
        #expect(!viewModel.canSave)

        viewModel.updateTitle("Ski trip")
        #expect(viewModel.canSave)
    }

    /// Its screen closes instead: the user came from the group.
    @Test func anEditSavedNamesNoGroupToGoTo() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = await form(callBacks)

        viewModel.updateTitle(" Ski trip 2027 ")
        viewModel.save()
        await settle { viewModel.isSaved }

        #expect(viewModel.isSaved)
        #expect(viewModel.createdGroupId == nil)
        // Saved without the whitespace around it.
        #expect(callBacks.edited.map(\.title) == ["Ski trip 2027"])
    }

    @Test func anEditThatWasNotSavedTellsWhyAndKeepsWhatWasTyped() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.editFailure = .outdated
        let viewModel = await form(callBacks)

        viewModel.updateTitle("Ski trip 2027")
        viewModel.save()
        await settle { viewModel.editError != nil }

        #expect(viewModel.editError == .outdated)
        #expect(!viewModel.isSaved)
        #expect(viewModel.form.title == "Ski trip 2027")
    }

    /// A creation the server refused deletes it under the form.
    @Test func theFormClosesOnceTheGroupIsGone() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = await form(callBacks)

        callBacks.shownGroup.send(nil)
        await settle { viewModel.isGone }

        #expect(viewModel.isGone)
        #expect(!viewModel.canSave)
    }

    /// Removed or left, the group is read-only: there is nothing left to edit.
    @Test func theFormClosesOnceTheUserIsNoLongerInTheGroup() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = await form(callBacks)

        callBacks.shownGroup.send(groupDetails(isLeft: true, canChangeMembers: false, canEdit: false, canDelete: false))
        await settle { viewModel.isGone }

        #expect(viewModel.isGone)
    }

    /// Saving once more would be the next edit of the group, made over the one just saved.
    @Test func anEditIsSavedOnceHoweverOftenItIsConfirmed() async {
        let callBacks = FakeGroupCallBacks()
        let gate = Gate()
        callBacks.gate = gate
        let viewModel = await form(callBacks)

        viewModel.updateTitle("Ski trip 2027")
        viewModel.save()
        viewModel.save()
        await settle()

        #expect(viewModel.isSaving)
        #expect(!viewModel.canSave)

        gate.open()
        await settle { viewModel.isSaved }
        viewModel.save()
        await settle()

        #expect(callBacks.edited.count == 1)
    }
}

/// Creating a group: who it is made of, as the form lists them, what it is saved as, and what the
/// list opens once it is created, as Android's `GroupEditFormTest` says the last.
@MainActor
struct GroupCreationFormTests {
    /// The user, and `picked` picked through the picker.
    private func form(_ callBacks: FakeGroupCallBacks = FakeGroupCallBacks(), picking picked: [String] = [ada]) async -> GroupFormViewModel {
        let viewModel = GroupFormViewModel(callBacks: callBacks, mode: .create)

        viewModel.beginPickingMembers()
        picked.forEach(viewModel.picker.toggle)
        await settle { viewModel.picker.contacts != nil }
        viewModel.confirmPickedMembers()
        await settle { Set(viewModel.names.known.keys) == Set(viewModel.form.memberUuids) }

        return viewModel
    }

    @Test func theFormOpensWithTheUserAloneInTheDefaultCurrency() async {
        let viewModel = GroupFormViewModel(callBacks: FakeGroupCallBacks(), mode: .create)

        #expect(viewModel.form.memberUuids == [me])
        #expect(viewModel.form.currency == euro)
        #expect(!viewModel.isBeingRead)
        #expect(!viewModel.canSave)
        // Nothing typed is no mistake to point out yet.
        #expect(viewModel.titleError == nil)
    }

    /// The user first, then the others by name, whatever order they were picked in.
    @Test func theMembersAreListedTheUserFirstThenByName() async {
        let viewModel = await form(picking: [carol, ada])

        #expect(viewModel.displayedMembers == [me, ada, carol])
    }

    @Test func membersUnpickedLeaveTheFormAndTheUserStays() async {
        let viewModel = await form(picking: [ada, bob])

        viewModel.beginPickingMembers()
        #expect(viewModel.picker.isPicked(ada))
        viewModel.picker.toggle(ada)
        viewModel.confirmPickedMembers()

        #expect(viewModel.displayedMembers == [me, bob])
    }

    /// The form keeps its members: the picker opens on them the next time.
    @Test func whatThePickerPickedIsDroppedWhenItIsCancelled() async {
        let viewModel = await form(picking: [ada])

        viewModel.beginPickingMembers()
        viewModel.picker.toggle(bob)
        viewModel.endPickingMembers()

        #expect(viewModel.form.memberUuids == [me, ada])

        viewModel.beginPickingMembers()
        #expect(viewModel.picker.selectedUuids == [ada])
    }

    /// Emptied as it closes, it would show nobody, and count the user alone, as it slides away.
    @Test func thePickerKeepsShowingWhatItPickedUntilItsSheetIsGone() async {
        let viewModel = GroupFormViewModel(callBacks: FakeGroupCallBacks(), mode: .create)

        viewModel.beginPickingMembers()
        await settle { viewModel.picker.found != nil && viewModel.picker.contacts != nil }
        viewModel.picker.toggle(ada)
        viewModel.picker.toggle(bob)
        viewModel.confirmPickedMembers()

        #expect(viewModel.form.memberUuids == [me, ada, bob])
        #expect(viewModel.picker.selectedUuids == [ada, bob])
        #expect(viewModel.picker.memberCount == 3)
        #expect(viewModel.picker.users.map(\.id) == [ada, bob, carol])

        viewModel.endPickingMembers()

        #expect(viewModel.picker.selectedUuids.isEmpty)
        #expect(viewModel.picker.found == nil)
        #expect(viewModel.picker.contacts == nil)
        #expect(viewModel.form.memberUuids == [me, ada, bob])
    }

    /// Confirmed with nobody picked, it leaves the user alone in the form.
    @Test func thePickerOfAGroupToCreateCanBeConfirmedWithNobodyPicked() async {
        let viewModel = GroupFormViewModel(callBacks: FakeGroupCallBacks(), mode: .create)

        viewModel.beginPickingMembers()

        #expect(viewModel.picker.canConfirm)
    }

    /// The user counts against the limit, as much as anybody picked.
    @Test func thePickerStopsWhereTheUserAndThosePickedFillTheGroup() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.maxMembers = 3
        let viewModel = GroupFormViewModel(callBacks: callBacks, mode: .create)

        viewModel.beginPickingMembers()
        viewModel.picker.toggle(ada)
        viewModel.picker.toggle(bob)
        viewModel.picker.toggle(carol)

        #expect(viewModel.picker.selectedUuids == [ada, bob])
        #expect(viewModel.picker.memberCount == 3)
        #expect(!viewModel.picker.isSelectable(carol))
        #expect(viewModel.picker.isSelectable(bob))
    }

    /// The screen opens this group once told the form is saved.
    @Test func aGroupCreatedIsTheOneTheListGoesTo() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = await form(callBacks, picking: [bob, ada])

        viewModel.updateTitle("  Ski trip ")
        viewModel.updateDescription("Chalet\n")
        viewModel.save()
        await settle { viewModel.isSaved }

        #expect(viewModel.isSaved)
        #expect(viewModel.createdGroupId == groupId)
        // Without the whitespace around them, and the others in the order the form lists them.
        #expect(callBacks.created.map(\.title) == ["Ski trip"])
        #expect(callBacks.created.map(\.description) == ["Chalet"])
        #expect(callBacks.created.map(\.memberUuids) == [[ada, bob]])
    }

    @Test func aGroupThatCouldNotBeCreatedLeavesTheFormWhereItIs() async {
        let callBacks = FakeGroupCallBacks()
        callBacks.creation = .failed(.failure(.unknown))
        let viewModel = await form(callBacks)

        viewModel.updateTitle("Ski trip")
        viewModel.save()
        await settle { viewModel.creationError != nil }

        #expect(!viewModel.isSaved)
        #expect(viewModel.createdGroupId == nil)
        #expect(viewModel.creationError == .failure(.unknown))
        #expect(viewModel.form.title == "Ski trip")
        #expect(viewModel.canSave)
    }

    /// Each creation is a group of its own.
    @Test func aGroupIsCreatedOnceHoweverOftenItIsConfirmed() async {
        let callBacks = FakeGroupCallBacks()
        let gate = Gate()
        let viewModel = await form(callBacks)

        callBacks.gate = gate
        viewModel.updateTitle("Ski trip")
        viewModel.save()
        viewModel.save()
        gate.open()
        await settle { viewModel.isSaved }
        viewModel.save()
        await settle()

        #expect(callBacks.created.count == 1)
    }

    @Test func theTitleSaysWhatIsWrongWithItOnceTyped() async {
        let viewModel = GroupFormViewModel(callBacks: FakeGroupCallBacks(), mode: .create)

        viewModel.updateTitle("   ")
        #expect(viewModel.titleError != nil)

        viewModel.updateTitle(String(repeating: "a", count: 50))
        #expect(viewModel.titleError == nil)

        viewModel.updateTitle(String(repeating: "a", count: 51))
        #expect(viewModel.titleError != nil)
        #expect(viewModel.titleError?.contains("51/50") == true)
    }

    @Test func theDescriptionSaysItIsTooLongCountedAsItIsSaved() async {
        let viewModel = GroupFormViewModel(callBacks: FakeGroupCallBacks(), mode: .create)

        viewModel.updateDescription(" \(String(repeating: "a", count: 1000)) ")
        #expect(viewModel.descriptionError == nil)

        viewModel.updateDescription(String(repeating: "a", count: 1001))
        // Written as the user's language writes numbers, "1 001" in French.
        #expect(viewModel.descriptionError?.contains("\(1001.formatted())/\(1000.formatted())") == true)
    }

    @Test func aCurrencyPickedIsTheGroups() async {
        let callBacks = FakeGroupCallBacks()
        let viewModel = await form(callBacks)

        viewModel.selectCurrency(callBacks.currencies[1])
        viewModel.updateTitle("Trip")
        viewModel.save()
        await settle { viewModel.isSaved }

        #expect(callBacks.created.map(\.currency.code) == ["USD"])
    }

    @Test func aFormWithAnythingInItHasEditsToLose() async {
        let viewModel = GroupFormViewModel(callBacks: FakeGroupCallBacks(), mode: .create)

        #expect(!viewModel.hasEdits)

        viewModel.updateTitle(" ")
        #expect(!viewModel.hasEdits)

        viewModel.updateDescription("Chalet")
        #expect(viewModel.hasEdits)
    }
}
