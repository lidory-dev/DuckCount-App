import Foundation
import Testing

@testable import Authentication

/// Answers as each test sets it, and records what the view models asked of it.
@MainActor
final class FakeAuthenticationCallBacks: AuthenticationCallBacks {
    let minimumPasswordLength = 15
    let maxUsernameLength = 32
    let maxFullNameLength = 10
    let termsOfUseLink = URL(string: "https://example.com/terms")!
    let privacyPolicyLink = URL(string: "https://example.com/privacy")!

    var leakCount: Int? = 0
    var registration: Result<String, AuthError> = .success("uuid")
    var loginFailure: AuthError?
    var profileFailure: AuthError?

    private(set) var registeredUsernames: [String] = []
    private(set) var loggedInUsernames: [String] = []
    private(set) var pendingProfiles: [PendingProfile] = []
    private(set) var savedFullNames: [String] = []

    func validateUsername(_ username: String) -> UsernameInputError? {
        username.first?.isLetter == true ? nil : .mustStartWithLetter
    }

    func passwordLeakCount(password: String) async -> Int? {
        leakCount
    }

    func register(username: String, password: String) async -> Result<String, AuthError> {
        registeredUsernames.append(username)

        return registration
    }

    func logIn(username: String, password: String) async -> AuthError? {
        loggedInUsernames.append(username)

        return loginFailure
    }

    func markPendingRegistration(_ profile: PendingProfile) {
        pendingProfiles.append(profile)
    }

    func setUpProfile(_ profile: PendingProfile, fullName: String) async -> AuthError? {
        savedFullNames.append(fullName)

        return profileFailure
    }
}

/// Waits for what a view model started in a task to settle.
@MainActor
private func settle(until isDone: () -> Bool) async {
    for _ in 0..<100 where !isDone() {
        await Task.yield()
    }
}

@MainActor
struct LoginViewModelTests {
    @Test func blankUsernameIsNoErrorButCannotLogIn() {
        let viewModel = LoginViewModel(callBacks: FakeAuthenticationCallBacks())

        viewModel.updateUsername("   ")
        viewModel.updatePassword("secret")

        #expect(viewModel.usernameError == nil)
        #expect(!viewModel.canLogIn)
    }

    @Test func usernameIsCheckedTrimmedAndLowercased() {
        let viewModel = LoginViewModel(callBacks: FakeAuthenticationCallBacks())

        viewModel.updateUsername(" 1alice")

        #expect(viewModel.usernameError == .mustStartWithLetter)
        #expect(viewModel.trimmedUsername == "1alice")
    }

    @Test func blankPasswordCannotLogIn() {
        let viewModel = LoginViewModel(callBacks: FakeAuthenticationCallBacks())

        viewModel.updateUsername("alice")
        viewModel.updatePassword("   ")

        #expect(!viewModel.canLogIn)
    }

    @Test func logsInWithTheTrimmedLowercasedUsername() async {
        let callBacks = FakeAuthenticationCallBacks()
        let viewModel = LoginViewModel(callBacks: callBacks)
        var isLoggedIn = false

        viewModel.updateUsername(" Alice ")
        viewModel.updatePassword("secret")
        viewModel.logIn { isLoggedIn = true }
        await settle { isLoggedIn }

        #expect(callBacks.loggedInUsernames == ["alice"])
        #expect(isLoggedIn)
        #expect(!viewModel.isLoading)
    }

    @Test func failureStaysOnTheFormUntilTheNextEdit() async {
        let callBacks = FakeAuthenticationCallBacks()
        callBacks.loginFailure = .invalidCredentials
        let viewModel = LoginViewModel(callBacks: callBacks)

        viewModel.updateUsername("alice")
        viewModel.updatePassword("secret")
        viewModel.logIn { Issue.record("Logged in") }
        await settle { viewModel.error != nil }

        #expect(viewModel.error == .invalidCredentials)

        viewModel.updatePassword("secret2")

        #expect(viewModel.error == nil)
    }
}

@MainActor
struct RegisterViewModelTests {
    private func filledIn(_ callBacks: FakeAuthenticationCallBacks) -> RegisterViewModel {
        let viewModel = RegisterViewModel(callBacks: callBacks)

        viewModel.updateUsername("Alice")
        viewModel.updatePassword("correct horse battery")
        viewModel.updatePasswordConfirmation("correct horse battery")

        return viewModel
    }

    @Test func passwordErrors() {
        let viewModel = RegisterViewModel(callBacks: FakeAuthenticationCallBacks())

        #expect(viewModel.passwordError == nil)

        viewModel.updatePassword("    ")
        #expect(viewModel.passwordError == .blank)

        viewModel.updatePassword("short")
        #expect(viewModel.passwordError == .tooShort)

        viewModel.updatePassword("fifteen chars!!")
        #expect(viewModel.passwordError == nil)
    }

    @Test func mismatchIsPointedOutOnceTheConfirmationIsTyped() {
        let viewModel = RegisterViewModel(callBacks: FakeAuthenticationCallBacks())

        viewModel.updatePassword("correct horse battery")
        #expect(!viewModel.isPasswordConfirmationMismatched)

        viewModel.updatePasswordConfirmation("correct")
        #expect(viewModel.isPasswordConfirmationMismatched)
        #expect(!viewModel.canRegister)
    }

    @Test func leakedPasswordStopsBeforeRegistering() async {
        let callBacks = FakeAuthenticationCallBacks()
        callBacks.leakCount = 3
        let viewModel = filledIn(callBacks)
        var leakCount: Int?

        viewModel.register(
            onPasswordLeaked: { leakCount = $0 },
            onRegistered: { _ in Issue.record("Registered") },
            onDeviceNotAllowed: { Issue.record("Device not allowed") }
        )
        await settle { leakCount != nil }

        #expect(leakCount == 3)
        #expect(callBacks.registeredUsernames.isEmpty)
        #expect(!viewModel.isLoading)
    }

    @Test func uncheckedPasswordStillRegistersAndIsMarkedSo() async {
        let callBacks = FakeAuthenticationCallBacks()
        callBacks.leakCount = nil
        let viewModel = filledIn(callBacks)
        var registered: PendingProfile?

        viewModel.register(
            onPasswordLeaked: { _ in Issue.record("Leaked") },
            onRegistered: { registered = $0 },
            onDeviceNotAllowed: { Issue.record("Device not allowed") }
        )
        await settle { registered != nil }

        let expected = PendingProfile(uuid: "uuid", username: "alice", hasPasswordBeenChecked: false)
        #expect(registered == expected)
        #expect(callBacks.pendingProfiles == [expected])
        #expect(viewModel.password.isEmpty)
    }

    @Test func refusedDeviceLeavesTheForm() async {
        let callBacks = FakeAuthenticationCallBacks()
        callBacks.registration = .failure(.deviceNotAllowed)
        let viewModel = filledIn(callBacks)
        var isRefused = false

        viewModel.register(
            onPasswordLeaked: { _ in Issue.record("Leaked") },
            onRegistered: { _ in Issue.record("Registered") },
            onDeviceNotAllowed: { isRefused = true }
        )
        await settle { isRefused }

        #expect(isRefused)
        #expect(viewModel.error == nil)
    }

    @Test func otherFailuresStayOnTheForm() async {
        let callBacks = FakeAuthenticationCallBacks()
        callBacks.registration = .failure(.usernameTaken)
        let viewModel = filledIn(callBacks)

        viewModel.register(
            onPasswordLeaked: { _ in Issue.record("Leaked") },
            onRegistered: { _ in Issue.record("Registered") },
            onDeviceNotAllowed: { Issue.record("Device not allowed") }
        )
        await settle { viewModel.error != nil }

        #expect(viewModel.error == .usernameTaken)
        #expect(callBacks.pendingProfiles.isEmpty)
    }
}

@MainActor
struct SetUpProfileViewModelTests {
    private let profile = PendingProfile(uuid: "uuid", username: "alice", hasPasswordBeenChecked: true)

    @Test func fullNameStopsAtTheLimitInUTF16Units() {
        let viewModel = SetUpProfileViewModel(profile: profile, callBacks: FakeAuthenticationCallBacks())

        // Nine units, then a character of two, which would make eleven.
        viewModel.updateFullName("Alice Mar😀")

        #expect(viewModel.fullName == "Alice Mar")
    }

    @Test func blankFullNameCannotBeSaved() {
        let viewModel = SetUpProfileViewModel(profile: profile, callBacks: FakeAuthenticationCallBacks())

        viewModel.updateFullName("  ")

        #expect(!viewModel.canSave)
    }

    @Test func savedOnceTheServerHoldsIt() async {
        let callBacks = FakeAuthenticationCallBacks()
        let viewModel = SetUpProfileViewModel(profile: profile, callBacks: callBacks)
        var isSaved = false

        viewModel.updateFullName("Alice")
        viewModel.save { isSaved = true }
        await settle { isSaved }

        #expect(isSaved)
        #expect(callBacks.savedFullNames == ["Alice"])
    }

    @Test func failureLetsTheUserTryAgain() async {
        let callBacks = FakeAuthenticationCallBacks()
        callBacks.profileFailure = .noInternet
        let viewModel = SetUpProfileViewModel(profile: profile, callBacks: callBacks)

        viewModel.updateFullName("Alice")
        viewModel.save { Issue.record("Saved") }
        await settle { viewModel.error != nil }

        #expect(viewModel.error == .noInternet)
        #expect(viewModel.canSave)
    }
}
