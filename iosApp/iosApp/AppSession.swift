import Shared
import SwiftUI

/// Starts the app over once its session has ended, as Android's `restartApp` starts it over in a new
/// process, which iOS has no way to do: whether the user logged out, deleted their account, or the
/// server ended the session for them, which this follows, see `SessionExpiry`.
///
/// Starting over takes every screen down, waits for the writes the user started, then has the
/// shared module close the database and drop everything it built for the session, see
/// `restartSession`, and builds every screen anew, which lands on the first screen of the app, with
/// whatever the user is to be told about why, as Android's relaunch tells it.
@MainActor
final class AppSession: ObservableObject {
    static let shared = AppSession()

    /// What the app tells the user once it started over, as Android's `RestartNotice`.
    enum Notice {
        /// The server ended the session.
        case sessionExpired

        /// The user deleted their account: the deletion said so, or the server ended the session
        /// after one whose answer never came back.
        case accountDeleted

        var message: Text {
            switch self {
            case .sessionExpired:
                Text("Your session has expired, please log in again.")
            case .accountDeleted:
                Text("Your account has been deleted.")
            }
        }
    }

    /// Changes as the app starts over, for every screen to be built anew, and with them everything
    /// they held of the session that ended.
    @Published private(set) var generation = 0

    /// Whether the app is starting over, during which no screen is up.
    @Published private(set) var isRestarting = false

    /// What the user is to be told about why they are back at the start, nil once they were.
    @Published var notice: Notice?

    private var sessionEnd: Task<Void, Never>?

    private init() {}

    /// Follows the server ending the session, from the session the shared module holds now: each
    /// start over builds one of its own. Called once the shared module is up.
    func followSessionEnd() {
        sessionEnd?.cancel()

        let ends = stream(KoinHelper().getSessionExpiry().end) { $0 as? SessionEnd }

        sessionEnd = Task { [weak self] in
            for await end in ends {
                guard let end else {
                    continue
                }

                // Kotlin enums cross over as classes, so the last case stands in for the default.
                let notice: Notice = switch end {
                case .accountDeleted: .accountDeleted
                default: .sessionExpired
                }

                await self?.restart(notice: notice)

                return
            }
        }
    }

    /// Starts the app over, once the session has ended on this device, telling the user `notice`.
    /// Once at a time: a session ends once.
    func restart(notice: Notice?) async {
        guard !isRestarting else {
            return
        }

        // The screens go first, which stops what they read, and none starts anything more.
        isRestarting = true

        await BackgroundTime.untilIdle()

        // Only Kotlin's cancellation crosses over, and nothing cancels this.
        try? await SessionRestartKt.restartSession()

        self.notice = notice
        generation += 1
        isRestarting = false

        followSessionEnd()
    }
}
