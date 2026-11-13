import ContactList
import Foundation
import Shared

/// The contact screens' callbacks, run by the shared module's `ContactManagement`, as Android's
/// `ContactCallBacksImp`, and the reservation part of its `SettingsCallBacksImpl`, run them in its
/// app module.
@MainActor
final class ContactCallBacksImpl: ContactCallBacks {
    private let contacts: ContactManagement

    init(contacts: ContactManagement = KoinHelper().getContactManagement()) {
        self.contacts = contacts
    }

    var maxUsernameLength: Int {
        Int(contacts.maxUsernameLength)
    }

    func authenticatedUserUuid() -> String? {
        contacts.authenticatedUserUuid()
    }

    func contacts(matching filter: String) -> AsyncStream<[ContactList.Contact]> {
        stream(contacts.users(filter: filter)) { value in
            (value as? [Shared.User] ?? []).map(\.contact)
        }
    }

    func contact(id: String) -> AsyncStream<ContactList.Contact> {
        // The shared flow only ever emits a user, so nothing is dropped here but what never comes.
        let users = stream(contacts.user(uuid: id)) { value in (value as? Shared.User)?.contact }

        return AsyncStream { continuation in
            let task = Task {
                for await contact in users {
                    if let contact {
                        continuation.yield(contact)
                    }
                }

                continuation.finish()
            }

            continuation.onTermination = { _ in
                task.cancel()
            }
        }
    }

    func reservedUsername() -> AsyncStream<String?> {
        stream(contacts.reservedUsername()) { value in value as? String }
    }

    func contactRequestCount() -> AsyncStream<Int> {
        stream(contacts.contactRequestCount()) { value in (value as? KotlinInt)?.intValue ?? 0 }
    }

    func contactRequests() -> AsyncStream<[ContactList.ContactRequest]> {
        stream(contacts.contactRequests()) { value in
            (value as? [Shared.ContactRequest] ?? []).map {
                ContactList.ContactRequest(id: $0.uuid, senderUserUuid: $0.senderUserUuid, senderFullName: $0.senderFullName)
            }
        }
    }

    func blockedUsers() -> AsyncStream<[ContactList.BlockedUser]> {
        stream(contacts.blockedUsers()) { value in
            (value as? [Shared.BlockedUser] ?? []).map { ContactList.BlockedUser(id: $0.userUuid, fullName: $0.fullName) }
        }
    }

    func verification(of contactId: String) -> AsyncStream<ContactList.ContactVerification> {
        stream(contacts.verification(userUuid: contactId)) { value in
            switch value {
            case let available as ContactVerificationStatusAvailable:
                .available(safetyNumber: available.safetyNumber, isVerified: available.isVerified)
            case is ContactVerificationStatusIdentityChanged:
                .identityChanged
            case is ContactVerificationStatusNotContact:
                .notContact
            case let unavailable as ContactVerificationStatusUnavailable:
                .unavailable(unavailable.failure.verificationError)
            default:
                .unavailable(.unknown)
            }
        }
    }

    func refresh() async {
        // Kotlin only throws its cancellation over to Swift.
        try? await contacts.refresh()
    }

    func removeContact(id: String) async -> ContactList.RemoveContactError? {
        do {
            return try await contacts.removeContact(userUuid: id)?.removeError
        } catch {
            return .unknown
        }
    }

    func blockContact(id: String) async -> ContactList.RemoveContactError? {
        do {
            return try await contacts.blockContact(userUuid: id)?.removeError
        } catch {
            return .unknown
        }
    }

    func unblock(userUuid: String) async {
        try? await contacts.unblock(userUuid: userUuid)
    }

    func markVerified(contactId: String, safetyNumber: String) async {
        try? await contacts.markVerified(userUuid: contactId, safetyNumber: safetyNumber)
    }

    func clearVerification(contactId: String) async {
        try? await contacts.clearVerification(userUuid: contactId)
    }

    func acceptRequest(id: String) async -> ContactList.AnswerContactRequestError? {
        do {
            return try await contacts.acceptRequest(requestUuid: id)?.answerError
        } catch {
            return .unknown
        }
    }

    func refuseRequest(id: String) async -> ContactList.AnswerContactRequestError? {
        do {
            return try await contacts.refuseRequest(requestUuid: id)?.answerError
        } catch {
            return .unknown
        }
    }

    func blockRequestSender(id: String) async -> ContactList.AnswerContactRequestError? {
        do {
            return try await contacts.blockRequestSender(requestUuid: id)?.answerError
        } catch {
            return .unknown
        }
    }

    func validateUsername(_ username: String) -> ContactList.UsernameInputError? {
        guard let failure = contacts.validateUsername(username: username) else {
            return nil
        }

        // Kotlin enums cross over as classes, so the last entry stands in for the default Swift
        // asks for.
        switch failure {
        case .mustStartWithLetter: return .mustStartWithLetter
        case .invalidCharacter: return .invalidCharacter
        default: return .tooLong
        }
    }

    func normalizeUsername(_ username: String) -> String {
        contacts.normalizeUsername(username: username)
    }

    func sendRequest(username: String) async -> ContactList.AddContactError? {
        do {
            return try await contacts.sendRequest(username: username)?.addError
        } catch {
            return .unknown
        }
    }

    func refreshReservedUsername() async {
        try? await contacts.refreshReservedUsername()
    }

    func reserveUsername(_ username: String) async -> ContactList.ReserveUsernameError? {
        do {
            return try await contacts.reserveUsername(username: username)?.reserveError
        } catch {
            return .unknown
        }
    }
}

extension Shared.User {
    fileprivate var contact: ContactList.Contact {
        ContactList.Contact(id: uuid, fullName: fullName)
    }
}

// Kotlin enums cross over as classes, so the last entry of each stands in for the default Swift
// asks for.

extension AddContactFailure {
    fileprivate var addError: ContactList.AddContactError {
        switch self {
        case .invalidUsername: .invalidUsername
        case .notFound: .notFound
        case .alreadyContact: .alreadyContact
        case .alreadyPending: .alreadyPending
        case .rateLimited: .rateLimited
        case .identityChanged: .identityChanged
        case .blocked: .blocked
        case .noInternet: .noInternet
        case .timeout: .timeout
        case .serverUnreachable: .serverUnreachable
        case .ownUsername: .selfUsername
        default: .unknown
        }
    }
}

extension AnswerContactRequestFailure {
    fileprivate var answerError: ContactList.AnswerContactRequestError {
        switch self {
        case .notFound: .notFound
        case .alreadyAccepted: .alreadyAccepted
        case .noInternet: .noInternet
        case .timeout: .timeout
        case .serverUnreachable: .serverUnreachable
        case .identityChanged: .identityChanged
        default: .unknown
        }
    }
}

extension RemoveContactFailure {
    fileprivate var removeError: ContactList.RemoveContactError {
        switch self {
        case .rateLimited: .rateLimited
        case .noInternet: .noInternet
        case .timeout: .timeout
        case .serverUnreachable: .serverUnreachable
        default: .unknown
        }
    }
}

extension ContactVerificationFailure {
    fileprivate var verificationError: ContactList.ContactVerificationError {
        switch self {
        case .noInternet: .noInternet
        case .timeout: .timeout
        case .serverUnreachable: .serverUnreachable
        default: .unknown
        }
    }
}

extension ReserveUsernameFailure {
    fileprivate var reserveError: ContactList.ReserveUsernameError {
        switch self {
        case .alreadyTaken: .alreadyTaken
        case .invalid: .invalid
        case .unauthorized: .unauthorized
        case .conflict: .conflict
        case .rateLimited: .rateLimited
        case .noInternet: .noInternet
        case .timeout: .timeout
        case .serverUnreachable: .serverUnreachable
        default: .unknown
        }
    }
}
