import PushSignals
import Shared
import UIKit

/// What the app does as a push wakes it, which iOS lets it do now and then: a background push always
/// asks it to, a visible one too, but iOS decides, and never wakes an app the user swiped away. The
/// counterpart of Android's `DuckCountPushService` and `PushSyncWorker`.
@MainActor
enum PushWake {
    /// How long a push that woke a locked device waits for an unlock before giving up: within the
    /// half a minute iOS gives the app, leaving the rest to the sync.
    private static let unlockWait: Duration = .seconds(15)

    /// Checks who sent the push `userInfo` carries, and for one who may wake the user, syncs, telling
    /// them what it read, see `Notifier`.
    static func handle(_ userInfo: [AnyHashable: Any]) async -> UIBackgroundFetchResult {
        guard let encrypted = PushPayload.encryptedSignal(in: userInfo) else {
            return .noData
        }

        // Read here as the extension reads it, so that neither lets through what the other refuses.
        guard PushPayload.mayWake(userInfo) != nil else {
            // A visible push the extension could not hide, lacking the filtering entitlement, is
            // taken down now: whoever sent it may not wake the user.
            await Notifier.shared.takeDown(pushCarrying: encrypted)

            return .noData
        }

        let foregroundSync = KoinHelper().getForegroundSync()

        // Nothing this device stores can be read while it is locked but the wake list and the keys.
        // The nameless text the extension showed, if any, is all that can be said until it unlocks,
        // and the next pass tells the rest: the one the app runs as the user opens it, or the
        // catch-up iOS runs in the background meanwhile, whichever comes first.
        guard await isUnlocked() else {
            try? await foregroundSync.announceNextPass()
            BackgroundCatchUp.shared.schedule(after: BackgroundCatchUp.afterLockedPush)

            return .noData
        }

        let isSynced = await withBackgroundTime {
            (try? await foregroundSync.syncForPush())?.boolValue ?? false
        }

        // Tried again later, as Android's push worker retries it: what failed is still on the server.
        if !isSynced {
            BackgroundCatchUp.shared.schedule(after: BackgroundCatchUp.afterFailedSync)
        }

        return isSynced ? .newData : .failed
    }

    /// Whether the device is unlocked, or is within `unlockWait`, as somebody hearing the push often
    /// unlocks it to look.
    private static func isUnlocked() async -> Bool {
        let deadline = ContinuousClock.now + unlockWait

        while !UIApplication.shared.isProtectedDataAvailable {
            guard ContinuousClock.now < deadline else {
                return false
            }

            try? await Task.sleep(for: .seconds(1))
        }

        return true
    }

    /// Runs `work` with time asked of iOS to finish it, which a sync may need more of than a push's
    /// wake gives. Not `BackgroundTime`, which a session's end waits for: a sync is stopped then
    /// instead, see `restartSession`.
    private static func withBackgroundTime<T>(_ work: () async -> T) async -> T {
        var identifier = UIBackgroundTaskIdentifier.invalid

        identifier = UIApplication.shared.beginBackgroundTask(withName: "push sync") {
            UIApplication.shared.endBackgroundTask(identifier)
            identifier = .invalid
        }

        defer {
            if identifier != .invalid {
                UIApplication.shared.endBackgroundTask(identifier)
            }
        }

        return await work()
    }
}
