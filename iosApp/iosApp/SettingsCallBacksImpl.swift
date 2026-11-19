import AppSettings
import Foundation
import Shared

/// The settings screens' callbacks, run by the shared module's `SettingsManagement`, as Android's
/// `SettingsCallBacksImpl` runs them in its app module. Ending the session starts the app over
/// within this process, see `AppSession`, where Android starts a new one.
///
/// What the screens write is given time to finish once the app leaves the foreground, as every
/// other write is, see `BackgroundTime`: a password change or a deletion the user believes done must
/// not be left halfway through.
@MainActor
final class SettingsCallBacksImpl: SettingsCallBacks {
    private let settings: SettingsManagement
    private let session: AppSession

    init(settings: SettingsManagement = KoinHelper().getSettingsManagement(), session: AppSession = .shared) {
        self.settings = settings
        self.session = session
    }

    var maxUsernameLength: Int {
        Int(settings.maxUsernameLength)
    }

    var maxFullNameLength: Int {
        Int(settings.maxFullNameLength)
    }

    var minimumPasswordLength: Int {
        Int(settings.minimumPasswordLength)
    }

    var learnMoreLink: URL {
        URL(string: settings.learnMoreLink)!
    }

    var sourceCodeLink: URL {
        URL(string: settings.sourceCodeLink)!
    }

    var termsOfUseLink: URL {
        URL(string: settings.termsOfUseLink)!
    }

    var privacyPolicyLink: URL {
        URL(string: settings.privacyPolicyLink)!
    }

    var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
    }

    /// What the build copied into the app from Gradle's list of what the Shared framework links,
    /// see `ios-licences`, read once.
    lazy var libraries: [AppSettings.Library] = {
        guard
            let url = Bundle.main.url(forResource: "licences", withExtension: "json"),
            let data = try? Data(contentsOf: url)
        else {
            return []
        }

        return (try? AppSettings.Library.decoding(aboutLibraries: data)) ?? []
    }()

    func authenticatedUserUuid() -> String? {
        settings.authenticatedUserUuid()
    }

    func authenticatedUsername() -> String? {
        settings.authenticatedUsername()
    }

    func fullName(of userUuid: String) -> AsyncStream<String?> {
        stream(settings.fullName(userUuid: userUuid)) { $0 as? String }
    }

    func refreshProfile(userUuid: String) async {
        // Kotlin only throws its cancellation over to Swift.
        try? await settings.refreshProfile(userUuid: userUuid)
    }

    func updateProfile(userUuid: String, fullName: String) async -> AppSettings.ProfileSaveError? {
        await BackgroundTime.whileFinishing("save profile") { [settings] in
            do {
                return try await settings.updateProfile(userUuid: userUuid, fullName: fullName)?.saveError
            } catch {
                return .unknown
            }
        }
    }

    func reservedUsername() -> AsyncStream<String?> {
        stream(settings.reservedUsername()) { $0 as? String }
    }

    func refreshReservedUsername() async {
        try? await settings.refreshReservedUsername()
    }

    func validateUsername(_ username: String) -> AppSettings.UsernameInputError? {
        guard let failure = settings.validateUsername(username: username) else {
            return nil
        }

        // Kotlin enums cross over as classes, so the last entry stands in for the default Swift
        // asks for.
        switch failure {
        case .mustStartWithLetter: return .mustStartWithLetter
        case .invalidCharacter: return .invalidCharacter
        default: return .tooLong
        }
    }

    func normalizeUsername(_ username: String) -> String {
        settings.normalizeUsername(username: username)
    }

    func reserveUsername(_ username: String) async -> AppSettings.ReserveUsernameError? {
        await BackgroundTime.whileFinishing("reserve username") { [settings] in
            do {
                return try await settings.reserveUsername(username: username)?.reserveError
            } catch {
                return .unknown
            }
        }
    }

    func changePassword(currentPassword: String, newPassword: String) async -> AppSettings.PasswordChangeError? {
        await BackgroundTime.whileFinishing("change password") { [settings] in
            do {
                return try await settings.changePassword(currentPassword: currentPassword, newPassword: newPassword)?.changeError
            } catch {
                return .unknown
            }
        }
    }

    func logOut() async {
        await BackgroundTime.whileFinishing("log out") { [settings] in
            try? await settings.logOut()
        }

        // A log-out needs no telling.
        await session.restart(notice: nil)
    }

    func deleteAccount(password: String) async -> AppSettings.AccountDeletionError {
        let failure: AccountDeletionFailure? = await BackgroundTime.whileFinishing("delete account") { [settings] in
            do {
                return try await settings.deleteAccount(password: password)
            } catch {
                return .unknown
            }
        }

        if let failure {
            return failure.deletionError
        }

        // The session ended with the account, and the app starts over, taking the screen that asked
        // down with it: what is returned here reaches nothing.
        await session.restart(notice: .accountDeleted)

        return .unknown
    }
}

// Kotlin enums cross over as classes, so the last entry of each stands in for the default Swift
// asks for.

extension ProfileSaveFailure {
    fileprivate var saveError: AppSettings.ProfileSaveError {
        switch self {
        case .noInternet: .noInternet
        case .timeout: .timeout
        case .serverUnreachable: .serverUnreachable
        case .unauthorized: .unauthorized
        case .conflict: .conflict
        case .fullNameTooLong: .fullNameTooLong
        default: .unknown
        }
    }
}

extension ReserveUsernameFailure {
    fileprivate var reserveError: AppSettings.ReserveUsernameError {
        switch self {
        case .alreadyTaken: .alreadyTaken
        case .invalid: .invalid
        case .unauthorized: .unauthorized
        case .conflict: .conflict
        case .rateLimited: .rateLimited
        case .noInternet: .noInternet
        case .timeout: .timeout
        case .serverUnreachable: .serverUnreachable
        default: .unknown
        }
    }
}

extension PasswordChangeRefusal {
    fileprivate var changeError: AppSettings.PasswordChangeError {
        switch failure {
        case .wrongPassword: .wrongPassword
        case .leaked: .leaked(count: Int(leakCount))
        case .noInternet: .noInternet
        case .timeout: .timeout
        case .serverUnreachable: .serverUnreachable
        case .unconfirmed: .unconfirmed
        default: .unknown
        }
    }
}

extension AccountDeletionFailure {
    fileprivate var deletionError: AppSettings.AccountDeletionError {
        switch self {
        case .wrongPassword: .wrongPassword
        case .noInternet: .noInternet
        case .timeout: .timeout
        case .serverUnreachable: .serverUnreachable
        case .unconfirmed: .unconfirmed
        default: .unknown
        }
    }
}
