import UIKit

/// Asks iOS for time to finish a write once the app leaves the foreground, which it suspends a few
/// seconds later otherwise: a group created, a member removed or an expense saved just before the
/// user switches apps would then wait, half sent, for the next time the app comes back, with nothing
/// to wake it as a push would on Android.
///
/// What runs out of time is not lost: the shared module saves what it sends before sending it, and
/// the next sync sends whatever is left.
@MainActor
enum BackgroundTime {
    static func whileFinishing<T>(_ name: String, _ work: @MainActor () async -> T) async -> T {
        let task = Task()

        task.identifier = UIApplication.shared.beginBackgroundTask(withName: name) {
            // Out of time: iOS suspends the app now, whatever is left being for the next sync.
            task.end()
        }

        defer {
            task.end()
        }

        return await work()
    }

    /// One background task, ended once, whichever of the write and iOS ends it first.
    @MainActor
    private final class Task {
        var identifier = UIBackgroundTaskIdentifier.invalid

        func end() {
            guard identifier != .invalid else {
                return
            }

            UIApplication.shared.endBackgroundTask(identifier)
            identifier = .invalid
        }
    }
}
