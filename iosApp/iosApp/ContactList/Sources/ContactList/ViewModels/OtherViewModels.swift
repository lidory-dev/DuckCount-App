import Foundation

/// The safety number screen's state, as Android's `ContactVerificationViewModel` holds it.
@MainActor
final class ContactVerificationViewModel: ObservableObject {
    /// The name on the contact's profile, nil until read: the safety number does not wait for it.
    @Published private(set) var fullName: String?

    /// Where the user stands with the contact, nil until known.
    @Published private(set) var verification: ContactVerification?

    /// Whether a mark is being written or cleared, which the user cannot start twice.
    @Published private(set) var isUpdating = false

    private let contactId: String
    private let callBacks: any ContactCallBacks
    private let contactTask = TaskHandle()
    private let verificationTask = TaskHandle()

    init(contactId: String, callBacks: any ContactCallBacks) {
        self.contactId = contactId
        self.callBacks = callBacks

        follow(callBacks.contact(id: contactId), owner: self, into: contactTask) { owner, contact in
            owner.fullName = contact.fullName.isEmpty ? nil : contact.fullName
        }

        follow(callBacks.verification(of: contactId), owner: self, into: verificationTask) { owner, verification in
            owner.verification = verification
        }
    }

    /// Marks the safety number shown verified, or clears the mark, as it is shown now.
    func toggleVerified() {
        guard !isUpdating, case .available(let safetyNumber, let isVerified) = verification else {
            return
        }

        isUpdating = true

        Task {
            if isVerified {
                await callBacks.clearVerification(contactId: contactId)
            } else {
                // The number the user read, which is the one they vouch for.
                await callBacks.markVerified(contactId: contactId, safetyNumber: safetyNumber)
            }

            isUpdating = false
        }
    }
}

/// The blocked users screen's state, as Android's `BlockedUserListViewModel` holds it.
@MainActor
final class BlockedUserListViewModel: ObservableObject {
    /// Everybody the user blocked, by name, people with no name last; nil until read.
    @Published private(set) var users: [BlockedUser]?

    private let callBacks: any ContactCallBacks
    private let usersTask = TaskHandle()

    init(callBacks: any ContactCallBacks) {
        self.callBacks = callBacks

        follow(callBacks.blockedUsers(), owner: self, into: usersTask) { owner, users in
            owner.users = users.sortedByName(selfUuid: nil, uuidOf: \.id, nameOf: \.fullName)
        }
    }

    /// Lifts the block on `user`, who leaves the list as it lands.
    func unblock(_ user: BlockedUser) {
        Task {
            await callBacks.unblock(userUuid: user.id)
        }
    }
}
