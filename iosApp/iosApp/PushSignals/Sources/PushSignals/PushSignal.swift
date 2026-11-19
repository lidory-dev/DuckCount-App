import Foundation

/// What a push signal says has arrived, and who sent it, as the shared module's `PushSignal` reads it:
/// the extension cannot load the shared module, so the two have to read a signal alike, and decide
/// alike who may wake the user.
public struct PushSignal: Equatable, Sendable {
    /// The kinds the server sends, see the shared module's `PushSignal.Kind`.
    public enum Kind: CaseIterable, Equatable, Sendable {
        case contactRequest
        case contactAccepted
        case message
        case oneTimeExpense
        case oneTimeExpenseChanged
        case groupWelcome
        case groupChanged
        case groupMemberLeft
        case groupExpense

        /// A kind this build does not know, or a payload it could not read at all.
        case unknown
    }

    public let kind: Kind

    /// The user whose action sent it, or nil when the payload names nobody this build can read.
    public let from: String?

    public init(kind: Kind, from: String?) {
        self.kind = kind
        self.from = from
    }

    /// Whether this signal may wake the user, by who sent it, see `WakeList`.
    ///
    /// A request to become a contact from anybody the user did not block, or from nobody named. An
    /// acceptance only from somebody the user sent a request still waiting for its answer. A group's
    /// expense, change or leave from a contact or from whoever shares a group the user is still in.
    /// Anything else only from a contact, and nothing naming nobody.
    public func mayWake(_ wakeList: WakeList) -> Bool {
        if kind == .contactRequest {
            guard let from else {
                return true
            }

            return !wakeList.isBlocked(from)
        }

        guard let from else {
            return false
        }

        switch kind {
        case .contactAccepted:
            return wakeList.isRequested(from)

        case .groupExpense, .groupChanged, .groupMemberLeft:
            return wakeList.isContact(from) || wakeList.isCoMember(from)

        default:
            return wakeList.isContact(from)
        }
    }

    /// Reads the signal a push carried, `{"type":…,"from":…}`, ignoring anything else in it as a newer
    /// server may add, and reading anything it cannot as an unknown kind from nobody.
    public static func parse(_ payload: Data) -> PushSignal {
        let unreadable = PushSignal(kind: .unknown, from: nil)

        guard
            let object = try? JSONSerialization.jsonObject(with: payload) as? [String: Any],
            let type = object["type"] as? String
        else {
            return unreadable
        }

        let from: String?

        switch object["from"] {
        case nil, is NSNull:
            from = nil
        case let value as String:
            from = value
        default:
            return unreadable
        }

        return PushSignal(kind: kind(named: type), from: from)
    }

    private static func kind(named type: String) -> Kind {
        switch type {
        case "contact_request": .contactRequest
        case "contact_accepted": .contactAccepted
        case "message": .message
        case "one_time_expense": .oneTimeExpense
        case "one_time_expense_changed": .oneTimeExpenseChanged
        case "group_welcome": .groupWelcome
        case "group_changed": .groupChanged
        case "group_member_left": .groupMemberLeft
        case "group_expense": .groupExpense
        default: .unknown
        }
    }
}

/// Reads a push's signal out of what APNs delivered.
public enum PushPayload {
    /// The key the server puts the encrypted signal under.
    public static let key = "e"

    /// The encrypted signal `userInfo` carries, or nil when it carries none.
    public static func encryptedSignal(in userInfo: [AnyHashable: Any]) -> String? {
        userInfo[key] as? String
    }

    /// The signal `userInfo` carries, decrypted with `keys`, or nil when it carries none, or none
    /// encrypted to them: a push for a session this device has left, or one that did not come from
    /// the server, which nothing is to be done about.
    public static func signal(in userInfo: [AnyHashable: Any], keys: PushKeys?) -> PushSignal? {
        guard
            let keys,
            let encrypted = encryptedSignal(in: userInfo),
            let body = Data(base64URLEncoded: encrypted),
            let plaintext = PushDecryption.decrypt(body, keys: keys)
        else {
            return nil
        }

        return PushSignal.parse(plaintext)
    }

    /// Whether the push `userInfo` carries may wake the user: one this device can read, from somebody
    /// on the wake list kept, see `PushSignal.mayWake`.
    public static func mayWake(
        _ userInfo: [AnyHashable: Any],
        keys: PushKeyStore = PushKeyStore(),
        wakeList: WakeListStorage = WakeListStorage()
    ) -> PushSignal? {
        guard let signal = signal(in: userInfo, keys: keys.load()), signal.mayWake(wakeList.read()) else {
            return nil
        }

        return signal
    }
}
