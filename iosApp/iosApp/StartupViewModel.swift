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

    /// Whether the launch screen is still up, see `LaunchScreenView`: on launch, until where the app
    /// starts is known and a second has passed, as Android's `MainActivity` holds its splash, the
    /// check being local and done in milliseconds. Never after a session ends, `AppSession` showing
    /// a screen of its own meanwhile.
    @Published private(set) var isLaunchScreenShown: Bool

    let authenticationCallBacks: AuthenticationCallBacksImpl

    private let authentication: AccountAuthentication

    /// Whether the app has already started once in this process, see `isLaunchScreenShown`.
    private static var hasLaunched = false

    private static let launchScreenMinimumDuration = Duration.seconds(1)

    init() {
        isLaunchScreenShown = !Self.hasLaunched
        Self.hasLaunched = true

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
        let shownAt = ContinuousClock.now
        let resolved: Startup

        if let pending = try? await authentication.pendingRegistration() {
            resolved = .resumeRegistration(
                PendingProfile(
                    uuid: pending.uuid,
                    username: pending.username,
                    hasPasswordBeenChecked: pending.hasPasswordBeenChecked
                )
            )
        } else if (try? await authentication.isLoggedIn())?.boolValue == true {
            resolved = .loggedIn
        } else {
            resolved = .notLoggedIn
        }

        if isLaunchScreenShown {
            // The first screen is only built once the launch screen is about to go, so that
            // nothing it asks, the notification permission for one, comes up over it.
            try? await Task.sleep(until: shownAt + Self.launchScreenMinimumDuration, clock: .continuous)
        }

        startup = resolved
        isLaunchScreenShown = false
    }
}
