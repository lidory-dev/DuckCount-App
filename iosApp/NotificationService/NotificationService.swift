import Foundation
import PushSignals
import UserNotifications

/// Decides, as a visible push arrives, whether the user sees it at all.
///
/// The server sends a visible push for what Android says from a signal alone on a locked phone: a
/// contact request, and a one-time expense arriving or changing. Its text is the app's own, nameless,
/// looked up by key from the app's String Catalog, and Apple carries nothing else readable: who sent
/// it is in the encrypted payload, see `PushPayload`. Every other signal is a background push, which
/// never comes here, and the app, woken by either when iOS lets it, syncs and puts the named
/// notification in the nameless one's place.
///
/// What this decides is whether the sender may wake the user, as Android's push service does before
/// anything else, see `PushSignal.mayWake`: a stranger may send a contact request, but nothing else,
/// and somebody the user blocked nothing at all. Hiding a push needs Apple's notification filtering
/// entitlement, which only Apple grants: until this extension holds it, set
/// `DuckCountHidesRefusedPushes` with it, a refused push shows its nameless text all the same, and
/// the app takes it down if iOS wakes it.
///
/// Everything here runs in a few milliseconds, well within the time iOS gives, and on a locked phone
/// too: the keys and the wake list are kept readable once the device has been unlocked since it
/// started.
final class NotificationService: UNNotificationServiceExtension {
    override func didReceive(
        _ request: UNNotificationRequest,
        withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        if PushPayload.mayWake(request.content.userInfo) != nil || !Self.mayHide {
            contentHandler(request.content)
        } else {
            // With the filtering entitlement, empty content shows nothing.
            contentHandler(UNNotificationContent())
        }
    }

    /// Whether this build holds the filtering entitlement, see `NotificationService`.
    private static let mayHide = Bundle.main.object(forInfoDictionaryKey: "DuckCountHidesRefusedPushes") as? Bool == true
}
