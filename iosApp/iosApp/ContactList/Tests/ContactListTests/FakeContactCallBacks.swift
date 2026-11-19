import Foundation

@testable import ContactList

let me = "me"
let ada = "ada"
let bob = "bob"

/// Answers as each test sets it, and records what the view models asked of it. Searches names as
/// the database does, the user among them, and checks a username as the library refuses a capital
/// or whitespace in one, and one past the length limit.
@MainActor
final class FakeContactCallBacks: ContactCallBacks {
    let maxUsernameLength = 12

    var people = [Contact(id: me, fullName: "Me"), Contact(id: ada, fullName: "Ada"), Contact(id: bob, fullName: "Bob")]
    var reserved: String? = "me"
    var requestCount = 0
    var requests: [ContactRequest] = []
    var blocked: [BlockedUser] = []
    var verifications: [String: ContactVerification] = [:]

    var removeFailure: RemoveContactError?
    var blockFailure: RemoveContactError?
    var answerFailure: AnswerContactRequestError?
    var addFailure: AddContactError?

    /// Holds every write back until opened, for a test to look at what is shown meanwhile.
    var gate: Gate?

    private(set) var removed: [String] = []
    private(set) var blockedContacts: [String] = []
    private(set) var unblocked: [String] = []
    private(set) var marked: [(String, String)] = []
    private(set) var cleared: [String] = []
    private(set) var accepted: [String] = []
    private(set) var refused: [String] = []
    private(set) var blockedSenders: [String] = []
    private(set) var sentTo: [String] = []
    private(set) var refreshCount = 0

    func authenticatedUserUuid() -> String? {
        me
    }

    func contacts(matching filter: String) -> AsyncStream<[Contact]> {
        let filter = filter.trimmingCharacters(in: .whitespaces)
        let found = filter.isEmpty ? people : people.filter { $0.fullName.localizedCaseInsensitiveContains(filter) }

        return AsyncStream { $0.yield(found) }
    }

    func contact(id: String) -> AsyncStream<Contact> {
        let contact = people.first { $0.id == id }

        return AsyncStream { continuation in
            if let contact {
                continuation.yield(contact)
            }
        }
    }

    func reservedUsername() -> AsyncStream<String?> {
        let reserved = reserved

        return AsyncStream { $0.yield(reserved) }
    }

    func contactRequestCount() -> AsyncStream<Int> {
        let count = requestCount

        return AsyncStream { $0.yield(count) }
    }

    func contactRequests() -> AsyncStream<[ContactRequest]> {
        let requests = requests

        return AsyncStream { $0.yield(requests) }
    }

    func blockedUsers() -> AsyncStream<[BlockedUser]> {
        let blocked = blocked

        return AsyncStream { $0.yield(blocked) }
    }

    func verification(of contactId: String) -> AsyncStream<ContactVerification> {
        let verification = verifications[contactId]

        return AsyncStream { continuation in
            if let verification {
                continuation.yield(verification)
            }
        }
    }

    func refresh() async {
        refreshCount += 1
        await gate?.wait()
    }

    func removeContact(id: String) async -> RemoveContactError? {
        removed.append(id)
        await gate?.wait()

        return removeFailure
    }

    func blockContact(id: String) async -> RemoveContactError? {
        blockedContacts.append(id)
        await gate?.wait()

        return blockFailure
    }

    func unblock(userUuid: String) async {
        unblocked.append(userUuid)
    }

    func markVerified(contactId: String, safetyNumber: String) async {
        marked.append((contactId, safetyNumber))
        await gate?.wait()
    }

    func clearVerification(contactId: String) async {
        cleared.append(contactId)
        await gate?.wait()
    }

    func acceptRequest(id: String) async -> AnswerContactRequestError? {
        accepted.append(id)
        await gate?.wait()

        return answerFailure
    }

    func refuseRequest(id: String) async -> AnswerContactRequestError? {
        refused.append(id)
        await gate?.wait()

        return answerFailure
    }

    func blockRequestSender(id: String) async -> AnswerContactRequestError? {
        blockedSenders.append(id)
        await gate?.wait()

        return answerFailure
    }

    func validateUsername(_ username: String) -> UsernameInputError? {
        if username.isEmpty {
            return nil
        }

        if username.count > maxUsernameLength {
            return .tooLong
        }

        if username != username.lowercased() || username.contains(where: \.isWhitespace) {
            return .invalidCharacter
        }

        return username.first!.isLetter ? nil : .mustStartWithLetter
    }

    func sendRequest(username: String) async -> AddContactError? {
        sentTo.append(username)
        await gate?.wait()

        return addFailure
    }
}

/// Holds back whoever waits on it until it is opened.
@MainActor
final class Gate {
    private var isOpen = false
    private var waiting: [CheckedContinuation<Void, Never>] = []

    func wait() async {
        guard !isOpen else {
            return
        }

        await withCheckedContinuation { waiting.append($0) }
    }

    func open() {
        isOpen = true
        waiting.forEach { $0.resume() }
        waiting = []
    }
}

/// Waits for what a view model started in a task to settle.
@MainActor
func settle(until isDone: () -> Bool) async {
    for _ in 0..<200 where !isDone() {
        await Task.yield()
    }
}
