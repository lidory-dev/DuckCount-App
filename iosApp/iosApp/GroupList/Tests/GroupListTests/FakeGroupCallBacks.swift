import Foundation

@testable import GroupList

let me = "me"
let ada = "ada"
let bob = "bob"
let carol = "carol"

let groupId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

let euro = GroupCurrency(id: "f4c08b14-5847-4d50-87b0-6ca4f3e961e1", code: "EUR", symbol: "€", hasSubunits: true)

/// A group as the shared module would map it for the user, its flags given as a test needs them.
func groupDetails(
    id: String = groupId,
    title: String = "Ski trip",
    description: String = "Chalet and lift passes",
    members: [GroupMember] = [GroupMember(id: me, isOwner: true), GroupMember(id: ada, isOwner: false)],
    isLeft: Bool = false,
    isDeleted: Bool = false,
    hasPendingChange: Bool = false,
    canChangeMembers: Bool = true,
    canEdit: Bool = true,
    canVouchForMembers: Bool = true,
    canLeave: Bool = false,
    canDelete: Bool = true
) -> GroupDetails {
    GroupDetails(
        id: id,
        title: title,
        description: description,
        currency: euro,
        members: members,
        isLeft: isLeft,
        isDeleted: isDeleted,
        hasPendingChange: hasPendingChange,
        canChangeMembers: canChangeMembers,
        canEdit: canEdit,
        canVouchForMembers: canVouchForMembers,
        canLeave: canLeave,
        canDelete: canDelete
    )
}

/// A value a stream follows: each stream asked for emits it at once, and again each time it is
/// sent anew, as a database query's flow does.
@MainActor
final class Subject<Element: Sendable> {
    private(set) var value: Element
    private var continuations: [UUID: AsyncStream<Element>.Continuation] = [:]

    init(_ value: Element) {
        self.value = value
    }

    /// How many streams of it are still followed.
    var followerCount: Int {
        continuations.count
    }

    func stream() -> AsyncStream<Element> {
        let (stream, continuation) = AsyncStream.makeStream(of: Element.self)
        let id = UUID()

        continuations[id] = continuation
        continuation.yield(value)
        continuation.onTermination = { [weak self] _ in
            Task { @MainActor in
                self?.continuations[id] = nil
            }
        }

        return stream
    }

    func send(_ value: Element) {
        self.value = value
        continuations.values.forEach { $0.yield(value) }
    }
}

/// Answers as each test sets it, and records what the view models asked of it. Lists contacts and
/// names as the database does, searching names as it does.
@MainActor
final class FakeGroupCallBacks: GroupCallBacks {
    let maxTitleLength = 50
    let maxDescriptionLength = 1000
    var maxMembers = 100

    let currencies = [euro, GroupCurrency(id: "usd", code: "USD", symbol: "$", hasSubunits: true)]

    var defaultCurrency: GroupCurrency {
        euro
    }

    let groupList = Subject<[GroupSummary]>([])
    let shownGroup = Subject<GroupDetails?>(groupDetails())
    let unreadable = Subject(0)
    let contacts = Subject<[Person]>([Person(id: ada, fullName: "Ada"), Person(id: bob, fullName: "Bob"), Person(id: carol, fullName: "Carol")])
    let names = Subject<[String: String]>([me: "Me", ada: "Ada", bob: "Bob", carol: "Carol"])
    let relationToMember = Subject(MemberRelation.unrelated)
    let groupBalances = Subject<GroupBalances?>(nil)

    var creation = GroupCreationResult.created(groupId: groupId)
    var editFailure: GroupEditError?
    var histories: [GroupHistory] = [.versions([])]
    var addition = MembershipChangeResult.made
    var removal = MembershipChangeResult.made
    var leaveFailure: GroupFailure?
    var deleteFailure: GroupFailure?
    var forgetFailure: GroupFailure?
    var requestFailure: MemberContactRequestError?
    var settlementFailure: GroupFailure?

    /// Holds every write back until opened, for a test to look at what is shown meanwhile.
    var gate: Gate?

    /// Holds back the names asked for from then on until opened, for a test to look at what is
    /// shown before they are read.
    var namesGate: Gate?

    private(set) var refreshCount = 0
    private(set) var refreshedGroups: [String] = []
    private(set) var created: [(title: String, description: String, currency: GroupCurrency, memberUuids: [String])] = []
    private(set) var edited: [(id: String, title: String, description: String)] = []
    private(set) var historyReads: [String] = []
    private(set) var added: [[String]] = []
    private(set) var removed: [String] = []
    private(set) var left: [String] = []
    private(set) var deleted: [String] = []
    private(set) var forgotten: [String] = []
    private(set) var requestsSent: [String] = []
    private(set) var settlements: [(debtorUuid: String, amount: String)] = []
    private(set) var searches: [String] = []

    func currencyDisplayName(code: String) -> String {
        code
    }

    func authenticatedUserUuid() -> String? {
        me
    }

    func groups() -> AsyncStream<[GroupSummary]> {
        groupList.stream()
    }

    func group(id: String) -> AsyncStream<GroupDetails?> {
        shownGroup.stream()
    }

    func unreadableExpenseCount(groupId: String) -> AsyncStream<Int> {
        unreadable.stream()
    }

    func invitableContacts(matching filter: String) -> AsyncStream<[Person]> {
        searches.append(filter)

        let filter = filter.trimmingCharacters(in: .whitespacesAndNewlines)
        let (stream, continuation) = AsyncStream.makeStream(of: [Person].self)
        let inner = contacts.stream()

        let task = Task {
            for await people in inner {
                continuation.yield(filter.isEmpty ? people : people.filter { $0.fullName.localizedCaseInsensitiveContains(filter) })
            }
        }

        continuation.onTermination = { _ in
            task.cancel()
        }

        return stream
    }

    func fullNames(of userUuids: Set<String>) -> AsyncStream<[String: String]> {
        let (stream, continuation) = AsyncStream.makeStream(of: [String: String].self)
        let inner = names.stream()
        let gate = namesGate

        let task = Task {
            await gate?.wait()

            for await names in inner {
                continuation.yield(names.filter { userUuids.contains($0.key) })
            }
        }

        continuation.onTermination = { _ in
            task.cancel()
        }

        return stream
    }

    func relation(to userUuid: String) -> AsyncStream<MemberRelation> {
        relationToMember.stream()
    }

    func balances(groupId: String) -> AsyncStream<GroupBalances?> {
        groupBalances.stream()
    }

    func refresh() async {
        refreshCount += 1
        await gate?.wait()
    }

    func refreshGroup(id: String) async {
        refreshedGroups.append(id)
        await gate?.wait()
    }

    func createGroup(title: String, description: String, currency: GroupCurrency, memberUuids: [String]) async -> GroupCreationResult {
        created.append((title, description, currency, memberUuids))
        await gate?.wait()

        return creation
    }

    func editGroup(id: String, title: String, description: String) async -> GroupEditError? {
        edited.append((id, title, description))
        await gate?.wait()

        return editFailure
    }

    func history(groupId: String) async -> GroupHistory {
        let read = historyReads.count

        historyReads.append(groupId)
        await gate?.wait()

        return histories[min(read, histories.count - 1)]
    }

    func addMembers(groupId: String, userUuids: [String]) async -> MembershipChangeResult {
        added.append(userUuids)
        await gate?.wait()

        return addition
    }

    func removeMember(groupId: String, userUuid: String) async -> MembershipChangeResult {
        removed.append(userUuid)
        await gate?.wait()

        return removal
    }

    func leave(groupId: String) async -> GroupFailure? {
        left.append(groupId)
        await gate?.wait()

        return leaveFailure
    }

    func delete(groupId: String) async -> GroupFailure? {
        deleted.append(groupId)
        await gate?.wait()

        return deleteFailure
    }

    func forget(groupId: String) async -> GroupFailure? {
        forgotten.append(groupId)
        await gate?.wait()

        return forgetFailure
    }

    func sendContactRequest(groupId: String, to userUuid: String) async -> MemberContactRequestError? {
        requestsSent.append(userUuid)
        await gate?.wait()

        return requestFailure
    }

    func recordSettlement(groupId: String, debtorUuid: String, amount: String) async -> GroupFailure? {
        settlements.append((debtorUuid, amount))
        await gate?.wait()

        return settlementFailure
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

/// Waits for what a view model started in a task, or a stream it follows, to settle.
@MainActor
func settle(until isDone: () -> Bool = { false }) async {
    for _ in 0..<200 where !isDone() {
        await Task.yield()
    }
}
