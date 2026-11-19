import PushSignals
import Shared
import UIKit
import UserNotifications

/// Gets this device ready to be woken once the user is signed in, and drops it once they are not,
/// as Android's `PushSetUp` and `PushTearDown` do.
@MainActor
enum PushSetUp {
    /// Asks for permission to notify, and for a token, on every launch of a signed-in app: iOS hands
    /// the token back each time, a changed one included, and the shared module sends nothing when it
    /// is the one it registered last.
    ///
    /// Asked here, the one place the app is in front of the user and known to have something to tell
    /// them about. The token is asked for first, whatever they answer, or before they answer at all:
    /// a refusal leaves pushes waking the app all the same, quietly.
    static func run() async {
        UIApplication.shared.registerForRemoteNotifications()

        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
    }

    /// Tells the server where to wake this device, with the keys it is to encrypt every signal to,
    /// made the first time and kept for the extension, see `PushKeyStore`.
    static func tokenReceived(_ token: Data) {
        // A token arriving after the session that asked for it ended is nobody's.
        guard KoinHelper().getSettingsManagement().authenticatedUserUuid() != nil else {
            return
        }

        guard let keys = PushKeyStore().loadOrCreate() else {
            print("PushSetUp: could not keep the push keys")

            return
        }

        let deviceToken = token.map { String(format: "%02x", $0) }.joined()

        Task {
            _ = try? await KoinHelper().getPushRegistrar().register(
                deviceToken: deviceToken,
                p256dh: keys.p256dh,
                auth: keys.auth
            )
        }
    }

    /// Drops this device's side of push as the session ends, the shared module having told the server
    /// to forget it on a log-out, see `SettingsManagement.logOut`.
    ///
    /// The token goes, so that a server that still holds it, after an expiry, finds it gone and stops
    /// pushing; the keys go, so that nothing pushed meanwhile can be read; what was said about the
    /// session that ended goes too, for the next one not to take it for its own; and so does the
    /// catch-up asked for, there being nothing left to catch up on.
    static func tearDown() {
        UIApplication.shared.unregisterForRemoteNotifications()

        PushKeyStore().delete()

        UNUserNotificationCenter.current().removeAllDeliveredNotifications()

        BackgroundCatchUp.shared.cancel()
    }
}
