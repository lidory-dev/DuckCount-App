import Foundation

// Why a group operation failed, at the granularity Android's `:feature:groupList` tells apart. The
// messages each case is shown with live in an extension of their own, with the String Catalog.

/// Why a change to a group could not be made, told in a sentence of its own, as Android's
/// `GroupCreationFailure`.
public enum GroupFailure: Error, Hashable, Sendable {
    case noInternet
    case timeout
    case serverUnreachable

    /// The user is no longer a member of the group.
    case groupLeft

    /// The server took too many changes from the user.
    case rateLimited

    /// The group waits for its owner to come online before taking this.
    case awaitingOwner

    case unknown
}

/// Why the server refused people a group was created with, or that were being added to it, as
/// Android's `MemberRefusal`.
public enum MemberRefusal: Hashable, Sendable {
    /// They are no contacts of the user.
    case notContacts

    /// The user's safety number with them changed.
    case identityChanged

    /// They cannot be invited for now: no key package to welcome them with.
    case unreachable

    /// They deleted their account.
    case deleted
}

/// Why a group could not be created, as Android's `GroupCreationError`.
public enum GroupCreationError: Error, Hashable, Sendable {
    case failure(GroupFailure)

    /// The server refused `names`, in the order the form lists them.
    case members(MemberRefusal, names: [String])
}

/// What creating a group gave.
public enum GroupCreationResult: Hashable, Sendable {
    /// The group is kept on the device, and sent to the server until it takes it.
    case created(groupId: String)

    case failed(GroupCreationError)
}

/// Why a group's title and description could not be edited, as Android's `GroupEditError`.
public enum GroupEditError: Error, Hashable, Sendable {
    case failure(GroupFailure)

    /// Its last change has not reached the server yet.
    case changePending

    /// Somebody edited it in the meantime: saving again writes over what they wrote.
    case outdated

    /// It already has this title and description.
    case unchanged
}

/// Why members could not be added to a group, or removed from it, as Android's
/// `MembershipChangeError`.
public enum MembershipChangeError: Error, Hashable, Sendable {
    case failure(GroupFailure)

    /// The server refused `names`, in the order the picker lists them.
    case members(MemberRefusal, names: [String])
}

/// What adding or removing members gave.
public enum MembershipChangeResult: Hashable, Sendable {
    /// The server took it.
    case made

    /// It is kept on the device, and sent as soon as the server can take it.
    case pending

    case failed(MembershipChangeError)
}

/// Why a group's history could not be read, as Android's `GroupHistoryError`.
public enum GroupHistoryError: Error, Hashable, Sendable {
    case failure(GroupFailure)

    /// The phone locked before it could be read.
    case deviceLocked

    /// It cannot be read on this device yet.
    case unreadable
}

/// Why a contact request to a member of a group could not be sent, as Android's groupList
/// `ContactRequestError`.
public enum MemberContactRequestError: Error, Hashable, Sendable {
    /// The member cannot receive one right now.
    case notFound

    case alreadyContact
    case alreadyPending
    case rateLimited

    /// The user's safety number with the member changed.
    case identityChanged

    /// The member's identity cannot be confirmed from this group on this device.
    case notInGroup

    /// The user blocked the member.
    case blocked

    case noInternet
    case timeout
    case serverUnreachable
    case unknown
}
