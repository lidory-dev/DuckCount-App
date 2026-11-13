import Foundation

/// Somebody this device holds a name for: one of the user's contacts, or the user themself.
public struct Contact: Identifiable, Hashable, Sendable {
    public let id: String

    /// The name on their profile, as they chose it: it may be empty, or start with anything.
    public let fullName: String

    public init(id: String, fullName: String) {
        self.id = id
        self.fullName = fullName
    }
}

/// A contact request waiting for the user's answer.
public struct ContactRequest: Identifiable, Hashable, Sendable {
    public let id: String
    public let senderUserUuid: String

    /// The name on the sender's profile, nil until it could be read.
    public let senderFullName: String?

    public init(id: String, senderUserUuid: String, senderFullName: String?) {
        self.id = id
        self.senderUserUuid = senderUserUuid
        self.senderFullName = senderFullName
    }
}

/// Somebody the user blocked.
public struct BlockedUser: Identifiable, Hashable, Sendable {
    /// Their user UUID.
    public let id: String

    /// The name the user saw them by as they blocked them, nil when there was none.
    public let fullName: String?

    public init(id: String, fullName: String?) {
        self.id = id
        self.fullName = fullName
    }
}

/// Where the user stands with checking that a contact is who this device talks to, as Android's
/// `ContactVerification`.
public enum ContactVerification: Hashable, Sendable {
    /// The safety number the user compares with the contact, twelve groups of five digits separated
    /// by spaces, and whether it is the one they marked verified.
    case available(safetyNumber: String, isVerified: Bool)

    /// The contact presented a security key other than the one recorded for them, which is either a
    /// new identity or an impersonation attempt, so there is no safety number to compare.
    case identityChanged

    /// They are not, or no longer, one of the user's contacts.
    case notContact

    /// The safety number could not be computed.
    case unavailable(ContactVerificationError)

    var isVerified: Bool {
        if case .available(_, true) = self {
            return true
        }

        return false
    }
}

/// What stands in for the name of somebody this device has none for.
var unresolvedUserName: String {
    String(localized: "Unresolved user", bundle: .module)
}

extension Contact {
    /// Their name, or what stands in for it while they have none.
    var displayName: String {
        fullName.isEmpty ? unresolvedUserName : fullName
    }
}
