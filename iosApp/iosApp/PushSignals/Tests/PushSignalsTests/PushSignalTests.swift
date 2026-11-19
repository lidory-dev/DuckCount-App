import Foundation
import Testing

@testable import PushSignals

private let sender = "6f1c2a3e-7b4d-4e8f-9a0b-1c2d3e4f5a6b"

/// Reading a signal as the shared module's `PushSignalTest` says it is read.
struct PushSignalTests {
    @Test(arguments: [
        ("contact_request", PushSignal.Kind.contactRequest),
        ("contact_accepted", .contactAccepted),
        ("message", .message),
        ("one_time_expense", .oneTimeExpense),
        ("one_time_expense_changed", .oneTimeExpenseChanged),
        ("group_welcome", .groupWelcome),
        ("group_changed", .groupChanged),
        ("group_member_left", .groupMemberLeft),
        ("group_expense", .groupExpense),
    ])
    func readsEveryKindTheServerSends(name: String, kind: PushSignal.Kind) {
        #expect(parse(#"{"type":"\#(name)","from":"\#(sender)"}"#) == PushSignal(kind: kind, from: sender))
    }

    @Test func readsASignalANewerServerAddedAsUnknown() {
        #expect(parse(#"{"type":"something_new","from":"\#(sender)"}"#) == PushSignal(kind: .unknown, from: sender))
    }

    @Test func ignoresFieldsANewerServerAdded() {
        #expect(
            parse(#"{"type":"message","from":"\#(sender)","extra":{"a":1}}"#)
                == PushSignal(kind: .message, from: sender)
        )
    }

    @Test func readsASignalNamingNoSenderAsFromNobody() {
        #expect(parse(#"{"type":"contact_request"}"#) == PushSignal(kind: .contactRequest, from: nil))
        #expect(parse(#"{"type":"contact_request","from":null}"#) == PushSignal(kind: .contactRequest, from: nil))
    }

    @Test(arguments: [
        "",
        "not json",
        "[]",
        #"{"from":"\#(sender)"}"#,
        #"{"type":1,"from":"\#(sender)"}"#,
        #"{"type":"message","from":7}"#,
    ])
    func readsAPayloadItCannotParseAsUnknownAndFromNobody(payload: String) {
        #expect(parse(payload) == PushSignal(kind: .unknown, from: nil))
    }

    private func parse(_ payload: String) -> PushSignal {
        PushSignal.parse(Data(payload.utf8))
    }
}

/// What the app keeps for the extension to read.
struct StorageTests {
    @Test func keysAreMadeOnceAndKept() throws {
        let store = PushKeyStore(store: MemoryStore())

        #expect(store.load() == nil)

        let made = try #require(store.loadOrCreate())
        let kept = try #require(store.load())

        #expect(kept.p256dh == made.p256dh)
        #expect(kept.auth == made.auth)
        #expect(store.loadOrCreate()?.p256dh == made.p256dh)
        #expect(Data(base64URLEncoded: made.p256dh)?.count == 65)
        #expect(Data(base64URLEncoded: made.auth)?.count == 16)
    }

    @Test func keysForgottenAreMadeAnew() throws {
        let store = PushKeyStore(store: MemoryStore())
        let first = try #require(store.loadOrCreate())

        store.delete()

        #expect(store.load() == nil)
        #expect(store.loadOrCreate()?.p256dh != first.p256dh)
    }

    @Test func keysThatCannotBeKeptAreNotHandedOut() {
        #expect(PushKeyStore(store: FailingStore()).loadOrCreate() == nil)
    }

    @Test func theWakeListIsReplacedWhole() {
        let storage = WakeListStorage(store: MemoryStore())
        let first = WakeList(contacts: ["a"], requested: ["b"], coMembers: ["c"], blocked: ["d"])
        let second = WakeList(contacts: ["e"], requested: [], coMembers: [], blocked: [])

        #expect(storage.read() == .nobody)

        storage.replace(first)
        #expect(storage.read() == first)

        storage.replace(second)
        #expect(storage.read() == second)

        storage.clear()
        #expect(storage.read() == .nobody)
    }

    @Test func aWakeListThatCannotBeReadNamesNobody() {
        let store = MemoryStore()
        store.write(Data("garbage".utf8), to: "wakeList")

        #expect(WakeListStorage(store: store).read() == .nobody)
    }
}

/// A store that keeps nothing.
private struct FailingStore: SecretStore {
    func read(_ account: String) -> Data? { nil }

    func write(_ data: Data, to account: String) -> Bool { false }

    func delete(_ account: String) {}
}
