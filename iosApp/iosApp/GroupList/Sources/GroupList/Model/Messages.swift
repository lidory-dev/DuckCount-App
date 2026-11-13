import Foundation

// What the group screens say of each failure, in the words Android's `:feature:groupList` says it
// in, each in the String Catalog with its French.

extension GroupFailure {
    /// What a screen says of it, as Android's `GroupCreationFailure.messageRes`: the same sentence
    /// whatever the change was.
    var message: String {
        switch self {
        case .noInternet:
            String(localized: "No connection, check your network and try again", bundle: .module)
        case .timeout:
            String(localized: "The server took too long to answer, please try again", bundle: .module)
        case .serverUnreachable:
            String(localized: "Server is unreachable, please try again later", bundle: .module)
        case .groupLeft:
            GroupNotices.left
        case .rateLimited:
            String(localized: "Too many changes, please try again later", bundle: .module)
        case .awaitingOwner:
            String(localized: "The group is waiting for its owner to come online, please try again later", bundle: .module)
        case .unknown:
            String(localized: "Something went wrong, please try again", bundle: .module)
        }
    }
}

// The refusals name everybody they apply to, and are worded for one of them or for several, as
// Android's plurals are: a count drives that, so it is their first argument, and their keys say
// which refusal they are rather than what they read, which the String Catalog holds, in English too.
extension MemberRefusal {
    /// What the form says when the server refused `names` a group was being created with, in the
    /// order it lists them, as Android's `MemberRefusal.messageRes`: every one of them, for the
    /// user to deal with all of them at once.
    func creationMessage(names: [String]) -> String {
        let count = names.count
        let listed = names.formatted(.list(type: .and))

        return switch self {
        case .notContacts:
            String(localized: "\(count) refused for not being contacts: \(listed)", bundle: .module)
        case .identityChanged:
            String(localized: "\(count) refused for a changed safety number: \(listed)", bundle: .module)
        case .unreachable:
            String(localized: "\(count) refused for being unreachable: \(listed)", bundle: .module)
        case .deleted:
            String(localized: "\(count) refused for a deleted account: \(listed)", bundle: .module)
        }
    }

    /// What the group's settings say when the server refused `names` being added to it, in the
    /// order the picker lists them, as Android's `MemberRefusal.additionMessageRes`: an addition's
    /// words, never a creation's, which say the group was not created.
    func additionMessage(names: [String]) -> String {
        let count = names.count
        let listed = names.formatted(.list(type: .and))

        return switch self {
        case .notContacts:
            String(localized: "\(count) not added for not being contacts: \(listed)", bundle: .module)
        case .identityChanged:
            String(localized: "\(count) not added for a changed safety number: \(listed)", bundle: .module)
        case .unreachable:
            String(localized: "\(count) not added for being unreachable: \(listed)", bundle: .module)
        case .deleted:
            String(localized: "\(count) not added for a deleted account: \(listed)", bundle: .module)
        }
    }
}

extension GroupCreationError {
    /// What the form says of it, as Android's `GroupCreationError.message`.
    var message: String {
        switch self {
        case .failure(let failure):
            failure.message
        case .members(let refusal, let names):
            refusal.creationMessage(names: names)
        }
    }
}

extension GroupEditError {
    /// What the form says of an edit that was not saved, as Android's `GroupEditError.messageRes`:
    /// what kept it from going through in a creation's words, and what is an edit's own in words of
    /// its own.
    var message: String {
        switch self {
        case .failure(let failure):
            failure.message
        case .changePending:
            String(localized: "The group can be edited once its last change reaches the server", bundle: .module)
        case .outdated:
            String(localized: "The group was edited in the meantime, please save again", bundle: .module)
        case .unchanged:
            String(localized: "The group already has this title and description", bundle: .module)
        }
    }
}

extension MembershipChangeError {
    /// What the settings say of it, as Android's `MembershipChangeError.message`.
    var message: String {
        switch self {
        case .failure(let failure):
            failure.message
        case .members(let refusal, let names):
            refusal.additionMessage(names: names)
        }
    }
}

extension MembershipChangeResult {
    /// What the settings say of it once answered, if anything, as Android's
    /// `MembershipChangeResult.message`: nothing of a change made, which the members listed show.
    var message: String? {
        switch self {
        case .made:
            nil
        case .pending:
            String(localized: "The change will be sent as soon as the server can take it", bundle: .module)
        case .failed(let error):
            error.message
        }
    }
}

extension GroupHistoryError {
    /// What the history says of it, as Android's `GroupHistoryError.messageRes`: what kept it from
    /// being read in a creation's words, and the device locking, and a history it cannot read yet,
    /// in words of their own.
    var message: String {
        switch self {
        case .failure(let failure):
            failure.message
        case .deviceLocked:
            String(localized: "The phone locked before the history could be read, please try again", bundle: .module)
        case .unreadable:
            String(localized: "The history can’t be read on this device yet, please try again in a moment", bundle: .module)
        }
    }
}

extension MemberContactRequestError {
    /// What a member's sheet says of a contact request to them that was not sent, as Android's
    /// groupList `ContactRequestError.messageRes`: in words of its own for each.
    var message: String {
        switch self {
        case .notFound:
            String(localized: "This member can’t receive a contact request right now, please try again later", bundle: .module)
        case .alreadyContact:
            String(localized: "This member is already one of your contacts", bundle: .module)
        case .alreadyPending:
            String(localized: "You already sent this member a contact request", bundle: .module)
        case .rateLimited:
            String(localized: "Too many attempts, please try again later", bundle: .module)
        case .identityChanged:
            String(localized: "Your safety number with this member has changed, so the request was not sent", bundle: .module)
        case .notInGroup:
            String(
                localized: "This member’s identity can’t be confirmed from this group on this device, so the request was not sent",
                bundle: .module
            )
        case .blocked:
            String(localized: "You blocked this member. Unblock them from your contacts to send a request.", bundle: .module)
        case .noInternet:
            String(localized: "No internet connection", bundle: .module)
        case .timeout:
            // The same English as a group change's, which French words differently, so a key of its
            // own.
            String(
                localized: "member_contact_request_error_timeout",
                defaultValue: "The server took too long to answer, please try again",
                bundle: .module
            )
        case .serverUnreachable:
            String(localized: "The server is unreachable, please try again later", bundle: .module)
        case .unknown:
            String(localized: "The contact request could not be sent", bundle: .module)
        }
    }
}

extension NobodyToPick {
    /// What the member picker says when it lists nobody, whatever is searched.
    var message: String {
        switch self {
        case .noContacts:
            String(localized: "You don’t have any contacts to add yet. Add some from the Contacts tab.", bundle: .module)
        case .allMembers:
            String(localized: "All the contacts you can add are already in this group", bundle: .module)
        }
    }
}

/// What the group's screens say of a group the user is no longer in, as Android's
/// `LeftGroupNotice` and `GroupCard` say it.
enum GroupNotices {
    /// Above the group, once the user is no longer a member of it, and why a change to it failed.
    static var left: String {
        String(localized: "You’re no longer a member of this group", bundle: .module)
    }

    /// Above the group, once its owner deleted it.
    static var deleted: String {
        String(localized: "The owner deleted this group. What you had of it stays on this device, read-only.", bundle: .module)
    }

    /// What is said of a group in the list once the user is no longer a member, or once its owner
    /// deleted it, which its title struck through shows.
    static func marker(isLeft: Bool, isDeleted: Bool) -> String? {
        if isDeleted {
            return String(localized: "Deleted by its owner", bundle: .module)
        }

        return isLeft ? String(localized: "No longer a member", bundle: .module) : nil
    }

    /// The notice above a group the user is no longer in: deleted by its owner, or left.
    static func notice(isDeleted: Bool) -> String {
        isDeleted ? deleted : left
    }
}
