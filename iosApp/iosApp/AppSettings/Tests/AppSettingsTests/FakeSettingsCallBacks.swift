import Foundation

@testable import AppSettings

let me = "me"

/// Answers as each test sets it, and records what the view models asked of it. Checks a username as
/// the library refuses a capital or whitespace in one, and one past the length limit.
@MainActor
final class FakeSettingsCallBacks: SettingsCallBacks {
    let maxUsernameLength = 12
    let maxFullNameLength = 10
    let minimumPasswordLength = 15
    let learnMoreLink = URL(string: "https://lidory.com")!
    let sourceCodeLink = URL(string: "https://example.com/source")!
    let termsOfUseLink = URL(string: "https://example.com/terms")!
    let privacyPolicyLink = URL(string: "https://example.com/privacy")!
    let appVersion = "1.0.0"
    var libraries: [Library] = []

    var userUuid: String? = me
    var username: String? = "ada"
    var fullName: String? = "Ada"
    var reserved: String? = "ada"

    var reserveFailure: ReserveUsernameError?
    var saveFailure: ProfileSaveError?
    var changeFailure: PasswordChangeError?
    var deletionFailure: AccountDeletionError = .wrongPassword

    /// Holds every write back until opened, for a test to look at what is shown meanwhile.
    var gate: Gate?

    private(set) var reservations: [String] = []
    private(set) var reservedRefreshCount = 0
    private(set) var profileRefreshes: [String] = []
    private(set) var savedNames: [String] = []
    private(set) var passwordChanges: [(current: String, new: String)] = []
    private(set) var logOutCount = 0
    private(set) var deletions: [String] = []

    func authenticatedUserUuid() -> String? {
        userUuid
    }

    func authenticatedUsername() -> String? {
        username
    }

    func fullName(of userUuid: String) -> AsyncStream<String?> {
        let fullName = fullName

        return AsyncStream { $0.yield(fullName) }
    }

    func refreshProfile(userUuid: String) async {
        profileRefreshes.append(userUuid)
    }

    func updateProfile(userUuid: String, fullName: String) async -> ProfileSaveError? {
        savedNames.append(fullName)
        await gate?.wait()

        return saveFailure
    }

    func reservedUsername() -> AsyncStream<String?> {
        let reserved = reserved

        return AsyncStream { $0.yield(reserved) }
    }

    func refreshReservedUsername() async {
        reservedRefreshCount += 1
    }

    func validateUsername(_ username: String) -> UsernameInputError? {
        if username.isEmpty {
            return nil
        }

        if username.count > maxUsernameLength {
            return .tooLong
        }

        if username != username.lowercased() || username.contains(where: \.isWhitespace) {
            return .invalidCharacter
        }

        return username.first!.isLetter ? nil : .mustStartWithLetter
    }

    func normalizeUsername(_ username: String) -> String {
        username.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    func reserveUsername(_ username: String) async -> ReserveUsernameError? {
        reservations.append(username)
        await gate?.wait()

        return reserveFailure
    }

    func changePassword(currentPassword: String, newPassword: String) async -> PasswordChangeError? {
        passwordChanges.append((currentPassword, newPassword))
        await gate?.wait()

        return changeFailure
    }

    func logOut() async {
        logOutCount += 1
        await gate?.wait()
    }

    func deleteAccount(password: String) async -> AccountDeletionError {
        deletions.append(password)
        await gate?.wait()

        return deletionFailure
    }
}

/// Holds back whoever waits on it until it is opened.
@MainActor
final class Gate {
    private var isOpen = false
    private var waiting: [CheckedContinuation<Void, Never>] = []

    func wait() async {
        guard !isOpen else {
            return
        }

        await withCheckedContinuation { waiting.append($0) }
    }

    func open() {
        isOpen = true
        waiting.forEach { $0.resume() }
        waiting = []
    }
}

/// Waits for what a view model started in a task to settle.
@MainActor
func settle(until isDone: () -> Bool) async {
    for _ in 0..<200 where !isDone() {
        await Task.yield()
    }
}
