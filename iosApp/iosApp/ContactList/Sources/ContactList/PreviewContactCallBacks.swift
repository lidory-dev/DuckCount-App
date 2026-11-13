import Foundation

/// Sample contacts for the previews, which reach no server.
@MainActor
final class PreviewContactCallBacks: ContactCallBacks {
    let maxUsernameLength = 32

    private let people = [
        Contact(id: "me", fullName: "Camille Martin"),
        Contact(id: "alice", fullName: "Alice Durand"),
        Contact(id: "bob", fullName: "Bob Leroy"),
        Contact(id: "emile", fullName: "Émile Zola"),
    ]

    func authenticatedUserUuid() -> String? {
        "me"
    }

    func contacts(matching filter: String) -> AsyncStream<[Contact]> {
        let found = filter.isBlank ? people : people.filter { $0.fullName.localizedCaseInsensitiveContains(filter) }

        return AsyncStream { $0.yield(found) }
    }

    func contact(id: String) -> AsyncStream<Contact> {
        let contact = people.first { $0.id == id } ?? people[1]

        return AsyncStream { $0.yield(contact) }
    }

    func reservedUsername() -> AsyncStream<String?> {
        AsyncStream { $0.yield("camille") }
    }

    func contactRequestCount() -> AsyncStream<Int> {
        AsyncStream { $0.yield(1) }
    }

    func contactRequests() -> AsyncStream<[ContactRequest]> {
        AsyncStream { $0.yield([ContactRequest(id: "request", senderUserUuid: "zoe", senderFullName: "Zoé Petit")]) }
    }

    func blockedUsers() -> AsyncStream<[BlockedUser]> {
        AsyncStream { $0.yield([BlockedUser(id: "mallory", fullName: "Mallory")]) }
    }

    func verification(of contactId: String) -> AsyncStream<ContactVerification> {
        AsyncStream {
            $0.yield(.available(safetyNumber: "12345 67890 13579 24680 11223 34455 66778 89900 10101 20202 30303 40404", isVerified: false))
        }
    }

    func refresh() async {}

    func removeContact(id: String) async -> RemoveContactError? { nil }

    func blockContact(id: String) async -> RemoveContactError? { nil }

    func unblock(userUuid: String) async {}

    func markVerified(contactId: String, safetyNumber: String) async {}

    func clearVerification(contactId: String) async {}

    func acceptRequest(id: String) async -> AnswerContactRequestError? { nil }

    func refuseRequest(id: String) async -> AnswerContactRequestError? { nil }

    func blockRequestSender(id: String) async -> AnswerContactRequestError? { nil }

    func validateUsername(_ username: String) -> UsernameInputError? {
        username.isEmpty || username.allSatisfy({ $0.isLetter || $0.isNumber }) ? nil : .invalidCharacter
    }

    func normalizeUsername(_ username: String) -> String {
        username.trimmedUsername
    }

    func sendRequest(username: String) async -> AddContactError? { nil }

    func refreshReservedUsername() async {}

    func reserveUsername(_ username: String) async -> ReserveUsernameError? { nil }
}
