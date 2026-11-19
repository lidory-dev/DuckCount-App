import PushSignals
import Shared

/// The wake list the shared module rebuilds, kept where the notification service extension reads it,
/// see `WakeListStorage`. Read whole on every look-up: a push is checked once, and the extension
/// reads it the same way.
final class AppWakeList: NSObject, SharedWakeList {
    private let storage = WakeListStorage()

    func isContact(userUuid: String) -> Bool { storage.read().isContact(userUuid) }

    func isRequested(userUuid: String) -> Bool { storage.read().isRequested(userUuid) }

    func isCoMember(userUuid: String) -> Bool { storage.read().isCoMember(userUuid) }

    func isBlocked(userUuid: String) -> Bool { storage.read().isBlocked(userUuid) }

    func replace(contacts: Set<String>, requested: Set<String>, coMembers: Set<String>, blocked: Set<String>) {
        storage.replace(WakeList(contacts: contacts, requested: requested, coMembers: coMembers, blocked: blocked))
    }

    func clear() {
        storage.clear()
    }
}
