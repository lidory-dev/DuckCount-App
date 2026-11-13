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

/// The username screen's state, reserving a first one or changing it, as Android's
/// `ReserveDiscoveryUsernameViewModel` holds it: both are the same call, the server replacing
/// whatever the user had reserved before, so only the wording differs.
@MainActor
final class ReserveUsernameViewModel: ObservableObject {
    /// The username reserved, as the device holds it.
    @Published private(set) var reservedUsername: String?

    /// Whether the reserved username was read, which the screen waits for.
    @Published private(set) var hasReadReservedUsername = false

    /// What the user typed, as typed, nil while the field still holds the reserved username.
    @Published private(set) var draft: String?

    @Published private(set) var isReserving = false

    /// Why the last reservation failed, cleared as soon as the username changes.
    @Published private(set) var error: ReserveUsernameError?

    /// True once the username is reserved, the only thing the screen is for, which closes it.
    @Published private(set) var isReserved = false

    let maxUsernameLength: Int

    private let callBacks: any ContactCallBacks
    private let reservedTask = TaskHandle()

    init(callBacks: any ContactCallBacks) {
        self.callBacks = callBacks
        self.maxUsernameLength = callBacks.maxUsernameLength

        follow(callBacks.reservedUsername(), owner: self, into: reservedTask) { owner, username in
            owner.reservedUsername = username
            owner.hasReadReservedUsername = true
        }

        // The vault is what says which handle the account holds, so the copy shown is refreshed
        // from it on every entry: it shows at once and is replaced when the read lands.
        Task {
            await callBacks.refreshReservedUsername()
        }
    }

    /// The text the field shows: as typed once edited, the reserved username until then.
    var fieldText: String {
        draft ?? reservedUsername ?? ""
    }

    /// The username as it is checked and reserved.
    var username: String {
        draft?.trimmedUsername ?? reservedUsername ?? ""
    }

    var hasReservedUsername: Bool {
        reservedUsername != nil
    }

    var inputError: UsernameInputError? {
        callBacks.validateUsername(username)
    }

    /// Whether the username is the one reserved already, which there is no point reserving again.
    var isUnchanged: Bool {
        guard let reservedUsername else {
            return false
        }

        return reservedUsername == callBacks.normalizeUsername(username)
    }

    var canReserve: Bool {
        !username.isBlank && inputError == nil && !isReserving && !isUnchanged && !isReserved
    }

    func updateUsername(_ username: String) {
        draft = username
        error = nil
    }

    func reserve() {
        guard canReserve else {
            return
        }

        let username = username

        isReserving = true
        error = nil

        Task {
            let failure = await callBacks.reserveUsername(username)

            if failure == nil {
                isReserved = true
            }

            isReserving = false
            error = failure
        }
    }
}
