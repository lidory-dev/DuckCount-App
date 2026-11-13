import Foundation

/// What the user could not do from a contact's screen, and why.
enum ContactDetailError: Equatable {
    /// Removing the contact failed.
    case removal(RemoveContactError)

    /// Blocking the contact failed, while removing them, which comes first.
    case blocking(RemoveContactError)

    var message: String {
        switch self {
        case .removal(let error): error.removalMessage
        case .blocking(let error): error.blockingMessage
        }
    }
}

/// A contact's screen's state, as Android's `ContactDetailViewModel` holds it.
@MainActor
final class ContactDetailViewModel: ObservableObject {
    /// The contact as the device holds them, nil until read.
    @Published private(set) var contact: Contact?

    /// Where the user stands with checking the contact's identity, nil until known.
    @Published private(set) var verification: ContactVerification?

    /// Whether the contact is being removed, which the user cannot start twice, nor block them meanwhile.
    @Published private(set) var isRemoving = false

    /// Whether the contact is being blocked, the same way.
    @Published private(set) var isBlocking = false

    /// True once the contact is removed, or blocked, which removes them, and leaves the screen
    /// nothing to show. A state rather than an event: it can complete while the app is in the
    /// background, and the screen closes once the user is back.
    @Published private(set) var isRemoved = false

    /// Why the last removal or blocking failed, until it is told.
    @Published var error: ContactDetailError?

    let contactId: String

    private let callBacks: any ContactCallBacks
    private let contactTask = TaskHandle()
    private let verificationTask = TaskHandle()

    init(contactId: String, callBacks: any ContactCallBacks) {
        self.contactId = contactId
        self.callBacks = callBacks

        follow(callBacks.contact(id: contactId), owner: self, into: contactTask) { owner, contact in
            owner.contact = contact
        }

        follow(callBacks.verification(of: contactId), owner: self, into: verificationTask) { owner, verification in
            owner.verification = verification
        }
    }

    /// Whether a removal or a blocking is under way or done, which nothing else is done during.
    var isBusy: Bool {
        isRemoving || isBlocking || isRemoved
    }

    /// Whether the contact's identity can be checked and the contact blocked from here: only a
    /// contact has an identity to check, and is blocked from here, as only a contact is removed:
    /// anybody else is blocked from a request of theirs. Waits until that is known.
    var isContact: Bool {
        guard let verification else {
            return false
        }

        return verification != .notContact
    }

    func remove() {
        guard !isBusy else {
            return
        }

        isRemoving = true

        Task {
            // By their UUID rather than the contact read: a contact whose name is not read yet is
            // removed as any other.
            let failure = await callBacks.removeContact(id: contactId)

            isRemoving = false

            if let failure {
                error = .removal(failure)
            } else {
                isRemoved = true
            }
        }
    }

    /// Blocks the contact, which removes them first.
    func block() {
        guard !isBusy else {
            return
        }

        isBlocking = true

        Task {
            let failure = await callBacks.blockContact(id: contactId)

            isBlocking = false

            if let failure {
                error = .blocking(failure)
            } else {
                isRemoved = true
            }
        }
    }
}
