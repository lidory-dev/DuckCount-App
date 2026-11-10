import Authentication
import Foundation
import Shared

/// The authentication screens' callbacks, run by the shared module's `AccountAuthentication`, as
/// Android's `AuthenticationCallBacksImpl` runs them in its app module.
@MainActor
final class AuthenticationCallBacksImpl: AuthenticationCallBacks {
    private let authentication: AccountAuthentication

    init(authentication: AccountAuthentication) {
        self.authentication = authentication
    }

    var minimumPasswordLength: Int {
        Int(authentication.minimumPasswordLength)
    }

    var maxUsernameLength: Int {
        Int(authentication.maxUsernameLength)
    }

    var maxFullNameLength: Int {
        Int(authentication.maxFullNameLength)
    }

    var termsOfUseLink: URL {
        URL(string: authentication.termsOfUseLink)!
    }

    var privacyPolicyLink: URL {
        URL(string: authentication.privacyPolicyLink)!
    }

    func validateUsername(_ username: String) -> UsernameInputError? {
        guard let error = authentication.validateUsername(username: username) else {
            return nil
        }

        // Kotlin enums cross over as classes, so the last entry stands in for the default Swift
        // asks for.
        switch error {
        case .empty: return .empty
        case .mustStartWithLetter: return .mustStartWithLetter
        case .invalidCharacter: return .invalidCharacter
        default: return .tooLong
        }
    }

    func passwordLeakCount(password: String) async -> Int? {
        (try? await authentication.passwordLeakCount(password: password))?.intValue
    }

    // Swift's, which the shared module's `Result` would otherwise shadow.
    func register(username: String, password: String) async -> Swift.Result<String, AuthError> {
        do {
            let outcome = try await authentication.register(username: username, password: password)

            if let registered = outcome as? RegistrationOutcomeRegistered {
                return .success(registered.uuid)
            }

            return .failure((outcome as? RegistrationOutcomeFailed)?.error.authError ?? .unknown)
        } catch {
            // Kotlin only throws its cancellation over to Swift.
            return .failure(.unknown)
        }
    }

    func logIn(username: String, password: String) async -> AuthError? {
        do {
            return try await authentication.logIn(username: username, password: password)?.authError
        } catch {
            return .unknown
        }
    }

    func markPendingRegistration(_ profile: PendingProfile) {
        authentication.markPendingRegistration(
            uuid: profile.uuid,
            username: profile.username,
            hasPasswordBeenChecked: profile.hasPasswordBeenChecked
        )
    }

    func setUpProfile(_ profile: PendingProfile, fullName: String) async -> AuthError? {
        do {
            return try await authentication.setUpProfile(
                uuid: profile.uuid,
                username: profile.username,
                fullName: fullName,
                hasPasswordBeenChecked: profile.hasPasswordBeenChecked
            )?.authError
        } catch {
            return .unknown
        }
    }
}

extension AuthenticationError {
    /// This error as the screens tell it.
    fileprivate var authError: AuthError {
        // Kotlin enums cross over as classes, so the last entry stands in for the default Swift
        // asks for.
        switch self {
        case .noInternet: .noInternet
        case .unreachable: .unreachable
        case .usernameTaken: .usernameTaken
        case .invalidCredentials: .invalidCredentials
        case .timeout: .timeout
        case .serverError: .serverError
        case .cryptoError: .cryptoError
        case .fullNameTooLong: .fullNameTooLong
        case .tooManyAttempts: .tooManyAttempts
        case .deviceNotAllowed: .deviceNotAllowed
        case .deviceCheckFailed: .deviceCheckFailed
        default: .unknown
        }
    }
}
