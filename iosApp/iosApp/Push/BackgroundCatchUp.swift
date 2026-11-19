import BackgroundTasks
import Shared
import UIKit

/// Has iOS run the push sync in the background later, where Android's WorkManager runs it on its
/// own: for a push that woke the app on a locked phone, which could read nothing, for one whose sync
/// failed, and for a send the server's limit held back, see `ForegroundResendScheduler`. Android waits
/// for the unlock and retries; iOS gives the app no unlock to wait for, so a background refresh task
/// stands in, asked for no sooner than the moment it is for.
///
/// It runs `ForegroundSync.syncForPush`, which tells the user what it read as Android's push worker
/// does, the sends owed included, a resend there being the push sync too.
///
/// Best effort, as everything iOS runs in the background: one request at a time, run no earlier than
/// asked and whenever iOS sees fit after that, seldom for an app the user rarely opens, and never for
/// one they swiped away. Whatever it misses waits for the next sync, a push's or a screen's.
final class BackgroundCatchUp: NSObject, CatchUpScheduler, @unchecked Sendable {
    static let shared = BackgroundCatchUp()

    /// The task's identifier, listed in the app's `BGTaskSchedulerPermittedIdentifiers`.
    static let identifier = "app.lidory.duckcount.catch-up"

    /// How soon a catch-up is asked for after a push the locked device could not sync for: soon, the
    /// user often unlocking the phone to look, iOS deciding when anyway.
    static let afterLockedPush: TimeInterval = 60

    /// How soon after a sync that failed, as Android's push worker retries it 30 seconds on.
    static let afterFailedSync: TimeInterval = 30

    /// How soon again after a catch-up iOS ran with the device locked, later than the first, each one
    /// spending what iOS allows the app.
    private static let afterLockedCatchUp: TimeInterval = 15 * 60

    /// How many times in a row a catch-up that failed asks for another, as Android's push worker
    /// retries a failing sync three times. Counted while the process lives.
    private static let maxRetries = 3

    private let requests = Requests()

    @MainActor private var retries = 0

    private override init() {}

    func catchUpAfter(seconds: Int64) {
        schedule(after: TimeInterval(seconds))
    }

    /// Asks for a catch-up no sooner than `interval` from now, unless one is asked for sooner.
    func schedule(after interval: TimeInterval) {
        let date = Date(timeIntervalSinceNow: max(interval, 0))

        Task {
            await requests.submit(notBefore: date)
        }
    }

    /// Drops the catch-up asked for, as the session ends: there is nothing left to catch up on.
    func cancel() {
        Task {
            await requests.cancel()
        }
    }

    /// Runs as iOS launches the task.
    @MainActor
    func run() async {
        await requests.ran()

        let koin = KoinHelper()

        // A session that ended meanwhile has nothing to catch up on, and an outdated build may ask
        // the server for nothing.
        guard
            koin.getSettingsManagement().authenticatedUserUuid() != nil,
            (koin.getUpdateRequirement().isRequired.value as? KotlinBoolean)?.boolValue != true
        else {
            return
        }

        // Nothing but the wake list and the push keys can be read on a locked phone.
        guard UIApplication.shared.isProtectedDataAvailable else {
            schedule(after: Self.afterLockedCatchUp)

            return
        }

        if (try? await koin.getForegroundSync().syncForPush())?.boolValue == true {
            retries = 0
        } else if retries < Self.maxRetries {
            // Twice as late each time, as Android's push worker backs off.
            schedule(after: Self.afterFailedSync * Double(1 << retries))
            retries += 1
        }
    }
}

/// The request iOS holds, one at a time, which a new one replaces: so the soonest is kept, as far as
/// this process knows of one.
private actor Requests {
    /// When the request this process last made is for, until the task runs or the request goes.
    private var scheduledFor: Date?

    func submit(notBefore date: Date) {
        if let scheduledFor, scheduledFor <= date {
            return
        }

        let request = BGAppRefreshTaskRequest(identifier: BackgroundCatchUp.identifier)
        request.earliestBeginDate = date

        do {
            try BGTaskScheduler.shared.submit(request)
            scheduledFor = date
        } catch {
            // Background refresh turned off for the app, or the request otherwise refused: the next
            // sync, a push's or a screen's, catches up instead.
            print("BackgroundCatchUp: could not ask for a catch-up: \(error)")
        }
    }

    func ran() {
        scheduledFor = nil
    }

    func cancel() {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: BackgroundCatchUp.identifier)
        scheduledFor = nil
    }
}
