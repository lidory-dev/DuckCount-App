import Foundation
import Testing

@testable import AppSettings

/// The list's header, which names the user.
@MainActor
struct SettingsViewModelTests {
    @Test func theHeaderNamesTheUserByTheirProfile() async {
        let callBacks = FakeSettingsCallBacks()
        let viewModel = SettingsViewModel(callBacks: callBacks)

        await settle { viewModel.fullName != nil && viewModel.reservedUsername != nil }

        #expect(viewModel.displayName == "Ada")
        #expect(viewModel.reservedUsername == "ada")
    }

    /// Right after a login, before the profile is read, or with a name of nothing but spaces.
    @Test func aUserWithNoNameIsNamedByTheirUsername() async {
        let callBacks = FakeSettingsCallBacks()
        callBacks.fullName = "  "
        callBacks.username = "ada4"
        let viewModel = SettingsViewModel(callBacks: callBacks)

        await settle { viewModel.reservedUsername != nil }

        #expect(viewModel.displayName == "ada4")
    }
}

/// The profile screen, as Android's `UserProfileScreenStateTest` says it.
@MainActor
struct ProfileViewModelTests {
    /// The server holds the profile, so it is read again on every entry.
    @Test func theSavedNameIsShownAndReadAgain() async {
        let callBacks = FakeSettingsCallBacks()
        let viewModel = ProfileViewModel(callBacks: callBacks)

        await settle { viewModel.hasReadProfile && callBacks.profileRefreshes.count == 1 }

        #expect(viewModel.fieldText == "Ada")
        #expect(callBacks.profileRefreshes == [me])
        #expect(!viewModel.canSave)
    }

    /// A space added around the saved name is nothing to save.
    @Test func theNameIsComparedWithoutTheWhitespaceAroundIt() async {
        let callBacks = FakeSettingsCallBacks()
        let viewModel = ProfileViewModel(callBacks: callBacks)
        await settle { viewModel.hasReadProfile }

        viewModel.updateFullName("Ada ")

        #expect(viewModel.fieldText == "Ada ")
        #expect(!viewModel.canSave)

        viewModel.updateFullName("Ada L ")

        #expect(viewModel.canSave)
    }

    @Test func aNameOfNothingButSpacesIsMissing() async {
        let callBacks = FakeSettingsCallBacks()
        let viewModel = ProfileViewModel(callBacks: callBacks)
        await settle { viewModel.hasReadProfile }

        viewModel.updateFullName("   ")

        #expect(viewModel.isNameMissing)
        #expect(!viewModel.canSave)
    }

    /// The field stops at the limit, a character at a time, never in the middle of one.
    @Test func theFieldTakesNothingPastTheLimit() async {
        let callBacks = FakeSettingsCallBacks()
        let viewModel = ProfileViewModel(callBacks: callBacks)
        await settle { viewModel.hasReadProfile }

        viewModel.updateFullName("Ada Lovelace")
        #expect(viewModel.fieldText == "Ada Lovela")

        // A duck takes two UTF-16 units, the limit's unit, so it does not fit in the last one.
        viewModel.updateFullName("Ada Lovel🦆")
        #expect(viewModel.fieldText == "Ada Lovel")
    }

    /// Saved without the whitespace around it, after which the field holds the name saved.
    @Test func aSavedNameIsSavedTrimmedAndShown() async {
        let callBacks = FakeSettingsCallBacks()
        let viewModel = ProfileViewModel(callBacks: callBacks)
        await settle { viewModel.hasReadProfile }

        viewModel.updateFullName(" Ada L ")
        viewModel.save()
        await settle { !viewModel.isSaving && viewModel.draft == nil }

        #expect(callBacks.savedNames == ["Ada L"])
        #expect(viewModel.fieldText == "Ada L")
        #expect(!viewModel.canSave)
    }

    /// A save the server did not take keeps what the user typed, for them to try again.
    @Test func aFailedSaveKeepsTheNameTyped() async {
        let callBacks = FakeSettingsCallBacks()
        callBacks.saveFailure = .noInternet
        let viewModel = ProfileViewModel(callBacks: callBacks)
        await settle { viewModel.hasReadProfile }

        viewModel.updateFullName("Ada L")
        viewModel.save()
        await settle { !viewModel.isSaving && viewModel.error != nil }

        #expect(viewModel.error == .noInternet)
        #expect(viewModel.fieldText == "Ada L")
        #expect(viewModel.canSave)

        viewModel.updateFullName("Ada Lo")

        #expect(viewModel.error == nil)
    }

    @Test func aSaveIsNotSentTwiceWhileOneIsUnderWay() async {
        let callBacks = FakeSettingsCallBacks()
        callBacks.gate = Gate()
        let viewModel = ProfileViewModel(callBacks: callBacks)
        await settle { viewModel.hasReadProfile }

        viewModel.updateFullName("Ada L")
        viewModel.save()
        viewModel.save()
        await settle { callBacks.savedNames.count == 1 }

        #expect(viewModel.isSaving)
        #expect(callBacks.savedNames == ["Ada L"])

        callBacks.gate?.open()
        await settle { !viewModel.isSaving }
    }

    /// Nobody signed in: nothing to read, nothing to save.
    @Test func aDeviceWithNobodySignedInHasNoProfile() async {
        let callBacks = FakeSettingsCallBacks()
        callBacks.userUuid = nil
        let viewModel = ProfileViewModel(callBacks: callBacks)

        await settle { false }

        #expect(viewModel.userUuid == nil)
        #expect(callBacks.profileRefreshes.isEmpty)
    }
}

/// The username screen, as Android's `ReserveDiscoveryUsernameViewModelTest` says it of a username
/// typed with capitals, or with whitespace around it.
@MainActor
struct ReserveUsernameViewModelTests {
    /// Read straight after the edit: the field takes its text back in the same frame. As typed, so
    /// that the field never hands the keyboard a new text.
    @Test func theUsernameTypedIsHeldAsTypedRightAfterTheEdit() {
        let callBacks = FakeSettingsCallBacks()
        callBacks.reserved = nil
        let viewModel = ReserveUsernameViewModel(callBacks: callBacks)

        viewModel.updateUsername("Ada4")

        #expect(viewModel.fieldText == "Ada4")
    }

    /// Lowercased, which the rules refuse a capital in.
    @Test func theUsernameTypedIsCheckedLowercased() {
        let callBacks = FakeSettingsCallBacks()
        callBacks.reserved = nil
        let viewModel = ReserveUsernameViewModel(callBacks: callBacks)

        viewModel.updateUsername("Ada4")

        #expect(viewModel.username == "ada4")
        #expect(viewModel.inputError == nil)
    }

    /// Held as typed, so that a space typed after a word is not swallowed, and checked and reserved
    /// without the whitespace around it, which the rules refuse as they refuse a space anywhere.
    @Test func theUsernameTypedIsReservedWithoutTheWhitespaceAroundIt() async {
        let callBacks = FakeSettingsCallBacks()
        callBacks.reserved = nil
        let viewModel = ReserveUsernameViewModel(callBacks: callBacks)
        await settle { viewModel.hasReadReservedUsername }

        viewModel.updateUsername(" Ada4 ")

        #expect(viewModel.fieldText == " Ada4 ")
        #expect(viewModel.username == "ada4")
        #expect(viewModel.inputError == nil)

        viewModel.reserve()
        await settle { viewModel.isReserved }

        #expect(callBacks.reservations == ["ada4"])
    }

    /// The vault says which username the account holds, so it is read again on every entry.
    @Test func theReservedUsernameIsShownAndReadAgain() async {
        let callBacks = FakeSettingsCallBacks()
        callBacks.reserved = "ada"
        let viewModel = ReserveUsernameViewModel(callBacks: callBacks)

        await settle { viewModel.hasReadReservedUsername && callBacks.reservedRefreshCount == 1 }

        #expect(viewModel.fieldText == "ada")
        #expect(viewModel.hasReservedUsername)
        #expect(callBacks.reservedRefreshCount == 1)
    }

    @Test func theUsernameReservedAlreadyIsNotReservedAgain() async {
        let callBacks = FakeSettingsCallBacks()
        callBacks.reserved = "ada"
        let viewModel = ReserveUsernameViewModel(callBacks: callBacks)
        await settle { viewModel.hasReadReservedUsername }

        #expect(!viewModel.canReserve)

        viewModel.updateUsername("Ada ")

        #expect(viewModel.isUnchanged)
        #expect(!viewModel.canReserve)

        viewModel.updateUsername("adele")

        #expect(viewModel.canReserve)
    }

    @Test func aUsernameNobodyCanHaveIsNotReserved() async {
        let callBacks = FakeSettingsCallBacks()
        callBacks.reserved = nil
        let viewModel = ReserveUsernameViewModel(callBacks: callBacks)
        await settle { viewModel.hasReadReservedUsername }

        viewModel.updateUsername("ada lovelace")
        #expect(viewModel.inputError == .invalidCharacter)

        viewModel.updateUsername("4da")
        #expect(viewModel.inputError == .mustStartWithLetter)

        viewModel.updateUsername("adalovelace13")
        #expect(viewModel.inputError == .tooLong)

        viewModel.reserve()
        await settle { false }

        #expect(callBacks.reservations.isEmpty)
    }

    /// Kept for the user to read, and cleared once they type something else.
    @Test func aFailureIsKeptUntilTheUsernameChanges() async {
        let callBacks = FakeSettingsCallBacks()
        callBacks.reserved = nil
        callBacks.reserveFailure = .alreadyTaken
        let viewModel = ReserveUsernameViewModel(callBacks: callBacks)
        await settle { viewModel.hasReadReservedUsername }

        viewModel.updateUsername("ada")
        viewModel.reserve()
        await settle { !viewModel.isReserving && viewModel.error != nil }

        #expect(viewModel.error == .alreadyTaken)
        #expect(!viewModel.isReserved)

        viewModel.updateUsername("adele")

        #expect(viewModel.error == nil)
    }
}
