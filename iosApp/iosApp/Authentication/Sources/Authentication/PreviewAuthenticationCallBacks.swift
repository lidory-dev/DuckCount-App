import Foundation

/// Stands in for the app's callbacks in previews: every username is valid but `taken`, every call
/// takes a second, and nothing is stored.
@MainActor
final class PreviewAuthenticationCallBacks: AuthenticationCallBacks {
    let minimumPasswordLength = 15
    let maxUsernameLength = 32
    let maxFullNameLength = 50
    let termsOfUseLink = URL(string: "https://lidory.com/duckcount/conditions-utilisation")!
    let privacyPolicyLink = URL(string: "https://lidory.com/duckcount/confidentialite")!

    func validateUsername(_ username: String) -> UsernameInputError? {
        if username.isEmpty {
            .empty
        } else if username.first?.isLetter != true {
            .mustStartWithLetter
        } else if !username.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber) }) {
            .invalidCharacter
        } else if username.count > maxUsernameLength {
            .tooLong
        } else {
            nil
        }
    }

    func passwordLeakCount(password: String) async -> Int? {
        try? await Task.sleep(for: .seconds(1))

        return password == "password1234567" ? 1 : 0
    }

    func register(username: String, password: String) async -> Result<String, AuthError> {
        try? await Task.sleep(for: .seconds(1))

        return username == "taken" ? .failure(.usernameTaken) : .success(UUID().uuidString)
    }

    func logIn(username: String, password: String) async -> AuthError? {
        try? await Task.sleep(for: .seconds(1))

        return .invalidCredentials
    }

    func markPendingRegistration(_ profile: PendingProfile) {}

    func setUpProfile(_ profile: PendingProfile, fullName: String) async -> AuthError? {
        try? await Task.sleep(for: .seconds(1))

        return nil
    }
}
