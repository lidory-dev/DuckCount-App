import Foundation

/// A currency a group keeps its accounts in, chosen at its creation and never changed.
public struct GroupCurrency: Identifiable, Hashable, Sendable {
    public let id: String

    /// Its ISO 4217 code, "EUR", which every device names it by.
    public let code: String

    public let symbol: String
    public let hasSubunits: Bool

    public init(id: String, code: String, symbol: String, hasSubunits: Bool) {
        self.id = id
        self.code = code
        self.symbol = symbol
        self.hasSubunits = hasSubunits
    }
}

/// A group as the list shows it: every group on the device, those the user is no longer in or
/// whose owner deleted them included, which stay read-only.
public struct GroupSummary: Identifiable, Hashable, Sendable {
    public let id: String
    public let title: String
    public let description: String

    /// Whether the user is no longer a member: they left, were removed, or the owner deleted it.
    public let isLeft: Bool

    /// Whether its owner deleted it, which nobody can take back. Implies `isLeft`.
    public let isDeleted: Bool

    public init(id: String, title: String, description: String, isLeft: Bool, isDeleted: Bool) {
        self.id = id
        self.title = title
        self.description = description
        self.isLeft = isLeft
        self.isDeleted = isDeleted
    }
}

/// A member of a group, by their UUID: their name comes from `GroupCallBacks.fullNames(of:)`.
public struct GroupMember: Identifiable, Hashable, Sendable {
    /// Their UUID.
    public let id: String

    /// Whether they created the group, which makes them its owner for as long as it exists.
    public let isOwner: Bool

    public init(id: String, isOwner: Bool) {
        self.id = id
        self.isOwner = isOwner
    }
}

/// A group with its members, and what the user may do with it, as the shared module works it out:
/// never recompute these flags here.
public struct GroupDetails: Identifiable, Hashable, Sendable {
    public let id: String
    public let title: String
    public let description: String
    public let currency: GroupCurrency
    public let members: [GroupMember]

    /// See `GroupSummary.isLeft`.
    public let isLeft: Bool

    /// See `GroupSummary.isDeleted`.
    public let isDeleted: Bool

    /// Whether a change of the group is still waiting for the server: its creation, or a change of
    /// its members. Members can be changed, and the group deleted, only once it has gone through.
    public let hasPendingChange: Bool

    /// Whether the user may add and remove members: its owner, still in it, once it is set up.
    public let canChangeMembers: Bool

    /// Whether the user may edit its title and description: its owner, still in it.
    public let canEdit: Bool

    /// Whether the group can vouch for its members' identities, which a contact request to one of
    /// them needs.
    public let canVouchForMembers: Bool

    /// Whether the user may leave it: a member still in it who is not its owner.
    public let canLeave: Bool

    /// Whether the user may delete it for everybody: its owner, still in it.
    public let canDelete: Bool

    public init(
        id: String,
        title: String,
        description: String,
        currency: GroupCurrency,
        members: [GroupMember],
        isLeft: Bool,
        isDeleted: Bool,
        hasPendingChange: Bool,
        canChangeMembers: Bool,
        canEdit: Bool,
        canVouchForMembers: Bool,
        canLeave: Bool,
        canDelete: Bool
    ) {
        self.id = id
        self.title = title
        self.description = description
        self.currency = currency
        self.members = members
        self.isLeft = isLeft
        self.isDeleted = isDeleted
        self.hasPendingChange = hasPendingChange
        self.canChangeMembers = canChangeMembers
        self.canEdit = canEdit
        self.canVouchForMembers = canVouchForMembers
        self.canLeave = canLeave
        self.canDelete = canDelete
    }
}

/// Somebody the user can add to a group: one of their contacts, never the user.
public struct Person: Identifiable, Hashable, Sendable {
    public let id: String
    public let fullName: String

    public init(id: String, fullName: String) {
        self.id = id
        self.fullName = fullName
    }
}

/// Where the user stands with a member of one of their groups, which says what the member's sheet
/// offers.
public enum MemberRelation: Hashable, Sendable {
    /// One of the user's contacts.
    case contact

    /// The member sent the user a contact request they have not answered.
    case requestReceived

    /// The user sent the member a contact request they have not answered.
    case requestSent

    /// None of the above. Not `none`, which an optional would read as nil.
    case unrelated
}

/// One version of a group's title and description, as its history shows it.
public struct GroupVersion: Identifiable, Hashable, Sendable {
    public let revision: Int64
    public let title: String
    public let description: String

    /// Whether this is the group as it was created, rather than as an edit left it.
    public let isCreation: Bool

    /// Whether this is the version the group shows now.
    public let isCurrent: Bool

    /// When the server took this version.
    public let revisedAt: Date

    public var id: Int64 { revision }

    public init(revision: Int64, title: String, description: String, isCreation: Bool, isCurrent: Bool, revisedAt: Date) {
        self.revision = revision
        self.title = title
        self.description = description
        self.isCreation = isCreation
        self.isCurrent = isCurrent
        self.revisedAt = revisedAt
    }
}

/// What reading a group's history gave: it is read from the server every time.
public enum GroupHistory: Sendable {
    /// Its versions, newest first.
    case versions([GroupVersion])

    case unavailable(GroupHistoryError)
}

/// Who owes whom in a group, from its members and its expenses that are not deleted, as the library
/// works it out.
public struct GroupBalances: Hashable, Sendable {
    /// What each person comes to, by UUID: positive when they are owed, negative when they owe, each
    /// a signed amount written as the library writes it, "-12.50". Names everybody an expense names,
    /// former members included, and current members with nothing at stake as zero, or not at all.
    public let netAmounts: [String: String]

    /// The group's current members, everybody else in `netAmounts` being a former member.
    public let memberUuids: Set<String>

    /// The repayments that would settle the group, as few as the library can make them.
    public let debts: [Debt]

    /// The group's currency, which every amount here is in.
    public let currency: GroupCurrency

    public init(netAmounts: [String: String], memberUuids: Set<String>, debts: [Debt], currency: GroupCurrency) {
        self.netAmounts = netAmounts
        self.memberUuids = memberUuids
        self.debts = debts
        self.currency = currency
    }
}

/// One repayment that settles a group: `debtorUuid` pays `creditorUuid` `amount`.
public struct Debt: Identifiable, Hashable, Sendable {
    public let debtorUuid: String
    public let creditorUuid: String

    /// A positive amount, written as the library writes it, "12.50".
    public let amount: String

    public var id: String { "\(debtorUuid)-\(creditorUuid)-\(amount)" }

    public init(debtorUuid: String, creditorUuid: String, amount: String) {
        self.debtorUuid = debtorUuid
        self.creditorUuid = creditorUuid
        self.amount = amount
    }
}
