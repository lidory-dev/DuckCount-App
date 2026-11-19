import Foundation

/// Who may wake the user, as the shared module's `WakeList` rebuilds it from the database: their
/// contacts, blocked ones aside, whoever they sent a contact request still waiting for its answer,
/// whoever shares a group they are still in with them, blocked ones aside, and whoever they blocked.
/// See `PushSignal.mayWake` for what each may wake them with.
public struct WakeList: Codable, Equatable, Sendable {
    public var contacts: Set<String>
    public var requested: Set<String>
    public var coMembers: Set<String>
    public var blocked: Set<String>

    /// Nobody, which is what a list that was never written, or cannot be read, names.
    public static let nobody = WakeList(contacts: [], requested: [], coMembers: [], blocked: [])

    public init(contacts: Set<String>, requested: Set<String>, coMembers: Set<String>, blocked: Set<String>) {
        self.contacts = contacts
        self.requested = requested
        self.coMembers = coMembers
        self.blocked = blocked
    }

    public func isContact(_ userUuid: String) -> Bool { contacts.contains(userUuid) }

    public func isRequested(_ userUuid: String) -> Bool { requested.contains(userUuid) }

    public func isCoMember(_ userUuid: String) -> Bool { coMembers.contains(userUuid) }

    public func isBlocked(_ userUuid: String) -> Bool { blocked.contains(userUuid) }
}

/// Keeps the `WakeList` where the extension reads it, one item replaced whole: a removal, or a block,
/// is never half written, and a reader never sees the contacts of one rebuild with the blocks of
/// another.
public struct WakeListStorage: Sendable {
    private static let account = "wakeList"

    private let store: SecretStore

    public init(store: SecretStore = KeychainStore.shared) {
        self.store = store
    }

    /// The list kept, or nobody when none is, or it cannot be read.
    public func read() -> WakeList {
        guard
            let data = store.read(Self.account),
            let list = try? JSONDecoder().decode(WakeList.self, from: data)
        else {
            return .nobody
        }

        return list
    }

    /// Replaces the list with `list`, and says whether it is kept.
    @discardableResult
    public func replace(_ list: WakeList) -> Bool {
        guard let data = try? JSONEncoder().encode(list) else {
            return false
        }

        return store.write(data, to: Self.account)
    }

    /// Forgets the list, as the session ends.
    public func clear() {
        store.delete(Self.account)
    }
}
