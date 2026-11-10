import Authentication
import Foundation
import Shared

/// Where the app lands on launch, resolved once from what this device stores, so that the first
/// screen the user sees is the right one, with no onboarding flashing by and no redirect after
/// launch, as Android's `AppViewModel` resolves it. The check is local only, so it is fast and
/// works offline.
@MainActor
final class StartupViewModel: ObservableObject {
    enum Startup {
        /// Still reading what the device stores: a neutral placeholder, never a real screen.
        case loading
        case loggedIn
        case notLoggedIn
        case resumeRegistration(PendingProfile)
    }

    @Published private(set) var startup = Startup.loading

    let authenticationCallBacks: AuthenticationCallBacksImpl

    private let authentication: AccountAuthentication

    init() {
        authentication = KoinHelper().getAccountAuthentication()
        authenticationCallBacks = AuthenticationCallBacksImpl(authentication: authentication)

        Task {
            await resolve()
        }
    }

    /// Leaves the authentication flow for the app, once the user is authenticated.
    func authenticated() {
        startup = .loggedIn
    }

    /// Publishes the user's key packages if they are due, which the app does whenever its main
    /// screen is shown, as Android's dashboard does: right after a login that could not publish
    /// them, that is the next chance.
    func publishKeyPackagesIfDue() async {
        try? await authentication.publishKeyPackagesIfDue()
    }

    private func resolve() async {
        if let pending = try? await authentication.pendingRegistration() {
            startup = .resumeRegistration(
                PendingProfile(
                    uuid: pending.uuid,
                    username: pending.username,
                    hasPasswordBeenChecked: pending.hasPasswordBeenChecked
                )
            )
        } else if (try? await authentication.isLoggedIn())?.boolValue == true {
            startup = .loggedIn
        } else {
            startup = .notLoggedIn
        }
    }
}
