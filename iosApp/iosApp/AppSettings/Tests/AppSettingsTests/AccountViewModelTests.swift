import Foundation
import Testing

@testable import AppSettings

/// The password change form, as Android's `PasswordChangeScreenStateTest` says it.
@MainActor
struct PasswordChangeViewModelTests {
    private let current = "current password 1"
    private let new = "brand new password 2"

    private func filledIn(_ callBacks: FakeSettingsCallBacks = FakeSettingsCallBacks()) -> PasswordChangeViewModel {
        let viewModel = PasswordChangeViewModel(callBacks: callBacks)
        viewModel.updateCurrentPassword(current)
        viewModel.updateNewPassword(new)
        viewModel.updateNewPasswordConfirmation(new)

        return viewModel
    }

    @Test func nothingTypedYetIsNoMistakeAndNothingToConfirm() {
        let viewModel = PasswordChangeViewModel(callBacks: FakeSettingsCallBacks())

        #expect(viewModel.newPasswordInputError == nil)
        #expect(!viewModel.isConfirmationMismatched)
        #expect(!viewModel.canConfirm)
    }

    @Test func aFilledInFormCanBeConfirmed() {
        #expect(filledIn().canConfirm)
    }

    @Test func aNewPasswordShorterThanTheMinimumIsTooShort() {
        let viewModel = filledIn()

        viewModel.updateNewPassword("short")

        #expect(viewModel.newPasswordInputError == .tooShort)
        #expect(!viewModel.canConfirm)
    }

    @Test func aNewPasswordOfNothingButSpacesIsRefused() {
        let viewModel = filledIn()
        let spaces = String(repeating: " ", count: 20)

        viewModel.updateNewPassword(spaces)
        viewModel.updateNewPasswordConfirmation(spaces)

        #expect(viewModel.newPasswordInputError == .blank)
        #expect(!viewModel.canConfirm)
    }

    /// Whitespace is part of a password: never trimmed.
    @Test func aNewPasswordWithSpacesAroundItCanBeSet() async {
        let callBacks = FakeSettingsCallBacks()
        let viewModel = filledIn(callBacks)
        let spaced = " \(new) "

        viewModel.updateNewPassword(spaced)
        viewModel.updateNewPasswordConfirmation(spaced)
        viewModel.confirm()
        await settle { viewModel.isChanged }

        #expect(callBacks.passwordChanges.map(\.new) == [spaced])
    }

    @Test func aCurrentPasswordOfNothingButSpacesCannotBeSent() {
        let viewModel = filledIn()

        viewModel.updateCurrentPassword("   ")

        #expect(!viewModel.canConfirm)
    }

    @Test func aNewPasswordThatIsTheCurrentOneMustDiffer() {
        let viewModel = filledIn()

        viewModel.updateNewPassword(current)
        viewModel.updateNewPasswordConfirmation(current)

        #expect(viewModel.newPasswordInputError == .sameAsCurrent)
        #expect(!viewModel.canConfirm)
    }

    @Test func aConfirmationThatDoesNotMatchIsPointedOutOnceTyped() {
        let viewModel = filledIn()

        viewModel.updateNewPasswordConfirmation("")
        #expect(!viewModel.isConfirmationMismatched)

        viewModel.updateNewPasswordConfirmation("brand new")
        #expect(viewModel.isConfirmationMismatched)
        #expect(!viewModel.canConfirm)
    }

    @Test func aChangeUnderWayCannotBeConfirmedAgain() async {
        let callBacks = FakeSettingsCallBacks()
        callBacks.gate = Gate()
        let viewModel = filledIn(callBacks)

        viewModel.confirm()
        viewModel.confirm()
        await settle { callBacks.passwordChanges.count == 1 }

        #expect(viewModel.isChanging)
        #expect(!viewModel.canConfirm)
        #expect(callBacks.passwordChanges.count == 1)

        callBacks.gate?.open()
        await settle { viewModel.isChanged }

        #expect(!viewModel.isChanging)
        #expect(!viewModel.canConfirm)
    }

    /// Sending it again would fail the same way.
    @Test func aFailureShownUnderAFieldKeepsTheFormFromBeingConfirmed() async {
        let callBacks = FakeSettingsCallBacks()
        callBacks.changeFailure = .wrongPassword
        let viewModel = filledIn(callBacks)

        viewModel.confirm()
        await settle { viewModel.error != nil }

        #expect(viewModel.error == .wrongPassword)
        #expect(!viewModel.isChanged)
        #expect(!viewModel.canConfirm)
    }

    @Test func aFailureShownUnderTheFormLeavesItToBeConfirmedAgain() async {
        let callBacks = FakeSettingsCallBacks()
        callBacks.changeFailure = .timeout
        let viewModel = filledIn(callBacks)

        viewModel.confirm()
        await settle { viewModel.error != nil }

        #expect(viewModel.error == .timeout)
        #expect(viewModel.canConfirm)
    }

    @Test func editingTheCurrentPasswordClearsItsFailureAndNotTheNewOnes() async {
        let callBacks = FakeSettingsCallBacks()
        callBacks.changeFailure = .wrongPassword
        let viewModel = filledIn(callBacks)
        viewModel.confirm()
        await settle { viewModel.error != nil }

        viewModel.updateNewPassword(new + "3")
        #expect(viewModel.error == .wrongPassword)

        viewModel.updateCurrentPassword(current + "4")
        #expect(viewModel.error == nil)
    }

    @Test func editingTheNewPasswordClearsItsFailureAndNotTheCurrentOnes() async {
        let callBacks = FakeSettingsCallBacks()
        callBacks.changeFailure = .leaked
        let viewModel = filledIn(callBacks)
        viewModel.confirm()
        await settle { viewModel.error != nil }

        viewModel.updateCurrentPassword(current + "4")
        #expect(viewModel.error == .leaked)

        viewModel.updateNewPassword(new + "3")
        #expect(viewModel.error == nil)
    }

    @Test func editingTheConfirmationKeepsTheFailuresShownUnderTheOtherFields() async {
        let callBacks = FakeSettingsCallBacks()
        callBacks.changeFailure = .leaked
        let viewModel = filledIn(callBacks)
        viewModel.confirm()
        await settle { viewModel.error != nil }

        viewModel.updateNewPasswordConfirmation(new + "3")

        #expect(viewModel.error == .leaked)
    }

    @Test func anyEditClearsAFailureShownUnderTheForm() async {
        let callBacks = FakeSettingsCallBacks()
        callBacks.changeFailure = .unconfirmed
        let viewModel = filledIn(callBacks)
        viewModel.confirm()
        await settle { viewModel.error != nil }

        viewModel.updateNewPasswordConfirmation(new)

        #expect(viewModel.error == nil)
    }
}

/// The account deletion form, whose password is the whole confirmation.
@MainActor
struct AccountDeletionViewModelTests {
    @Test func aPasswordOfNothingButSpacesIsNoPassword() async {
        let callBacks = FakeSettingsCallBacks()
        let viewModel = AccountDeletionViewModel(callBacks: callBacks)

        #expect(!viewModel.canDelete)

        viewModel.updatePassword("   ")
        viewModel.delete()
        await settle { false }

        #expect(!viewModel.canDelete)
        #expect(callBacks.deletions.isEmpty)
    }

    /// Sent as typed, whitespace being part of a password.
    @Test func thePasswordIsSentAsTypedAndOnce() async {
        let callBacks = FakeSettingsCallBacks()
        callBacks.gate = Gate()
        let viewModel = AccountDeletionViewModel(callBacks: callBacks)

        viewModel.updatePassword(" password ")
        viewModel.delete()
        viewModel.delete()
        await settle { callBacks.deletions.count == 1 }

        #expect(viewModel.isDeleting)
        #expect(callBacks.deletions == [" password "])

        callBacks.gate?.open()
        await settle { !viewModel.isDeleting }
    }

    /// Kept for the user to read, and cleared once they type again.
    @Test func aFailureIsKeptUntilThePasswordChanges() async {
        let callBacks = FakeSettingsCallBacks()
        callBacks.deletionFailure = .unconfirmed
        let viewModel = AccountDeletionViewModel(callBacks: callBacks)

        viewModel.updatePassword("password")
        viewModel.delete()
        await settle { viewModel.error != nil }

        #expect(viewModel.error == .unconfirmed)
        #expect(viewModel.canDelete)

        viewModel.updatePassword("password2")

        #expect(viewModel.error == nil)
    }
}

@MainActor
struct AccountViewModelTests {
    @Test func theUserIsLoggedOutOnce() async {
        let callBacks = FakeSettingsCallBacks()
        callBacks.gate = Gate()
        let viewModel = AccountViewModel(callBacks: callBacks)

        #expect(viewModel.username == "ada")

        viewModel.logOut()
        viewModel.logOut()
        await settle { callBacks.logOutCount == 1 }

        #expect(viewModel.isLoggingOut)
        #expect(callBacks.logOutCount == 1)

        callBacks.gate?.open()
    }
}

/// The libraries the AboutLibraries plugin writes, as the Licences screen reads them.
struct LibraryTests {
    private let file = """
        {
          "libraries": [
            {
              "uniqueId": "io.ktor:ktor-client-core",
              "name": "ktor-client-core",
              "artifactVersion": "3.5.1",
              "developers": [{"name": "Jetbrains Team"}, {"name": " "}],
              "scm": {"url": "https://github.com/ktorio/ktor"},
              "licenses": ["Apache-2.0", "unknown"]
            },
            {
              "uniqueId": "net.zetetic:SQLCipher.swift",
              "name": "SQLCipher",
              "artifactVersion": "4.19.0",
              "website": "https://www.zetetic.net/sqlcipher/",
              "organization": {"name": "Zetetic LLC"},
              "developers": [{"name": "Stephen"}],
              "licenses": ["sqlcipher-community"]
            },
            {
              "uniqueId": "org.example:nameless",
              "licenses": []
            }
          ],
          "licenses": {
            "Apache-2.0": {"name": "Apache License 2.0", "content": "Apache License", "url": "https://spdx.org/licenses/Apache-2.0.html"},
            "sqlcipher-community": {"name": "SQLCipher Community Edition Open Source License", "content": "Copyright"}
          }
        }
        """

    @Test func librariesAreReadByNameWithTheirLicences() throws {
        let libraries = try Library.decoding(aboutLibraries: Data(file.utf8))

        #expect(libraries.map(\.name) == ["ktor-client-core", "org.example:nameless", "SQLCipher"])

        let ktor = libraries[0]
        #expect(ktor.version == "3.5.1")
        #expect(ktor.author == "Jetbrains Team")
        #expect(ktor.website == URL(string: "https://github.com/ktorio/ktor"))
        #expect(ktor.licences == [
            Licence(name: "Apache License 2.0", text: "Apache License", url: URL(string: "https://spdx.org/licenses/Apache-2.0.html")),
        ])

        let sqlCipher = libraries[2]
        #expect(sqlCipher.author == "Zetetic LLC")
        #expect(sqlCipher.website == URL(string: "https://www.zetetic.net/sqlcipher/"))
        #expect(sqlCipher.licences.map(\.name) == ["SQLCipher Community Edition Open Source License"])

        #expect(libraries[1].version == nil)
        #expect(libraries[1].author == nil)
    }

    @Test func aFileThatIsNotTheirsIsRefused() {
        #expect(throws: (any Error).self) {
            try Library.decoding(aboutLibraries: Data("{}".utf8))
        }
    }
}
