import Testing

@testable import PushSignals

private let alice = "11111111-1111-1111-1111-111111111111"
private let bob = "33333333-3333-3333-3333-333333333333"
private let carol = "55555555-5555-5555-5555-555555555555"
private let mallory = "77777777-7777-7777-7777-777777777777"
private let trent = "88888888-8888-8888-8888-888888888888"

/// Who may wake the user, as the shared module's `WakeDecisionTest` says it, which the extension has
/// to agree with: Bob is a contact, and Alice has a contact request from the user she has yet to
/// answer. Carol shares a group the user is still in with them, and is no contact. Mallory is a
/// stranger, or a former contact, which comes to the same. Trent is somebody the user blocked.
struct WakeDecisionTests {
    private let wakeList = WakeList(
        contacts: [bob],
        requested: [alice],
        coMembers: [carol],
        blocked: [trent]
    )

    @Test func aContactRequestWakesTheUserWhoeverSentIt() {
        #expect(signal(.contactRequest, from: mallory).mayWake(wakeList))
        #expect(signal(.contactRequest, from: bob).mayWake(wakeList))
        #expect(signal(.contactRequest, from: nil).mayWake(wakeList))
    }

    @Test func aContactRequestFromSomebodyTheUserBlockedDoesNotWakeTheUser() {
        #expect(!signal(.contactRequest, from: trent).mayWake(wakeList))
    }

    /// A stranger he is: blocking a contact removes them.
    @Test(arguments: PushSignal.Kind.allCases)
    func nothingFromSomebodyTheUserBlockedWakesTheUser(kind: PushSignal.Kind) {
        #expect(!signal(kind, from: trent).mayWake(wakeList))
    }

    @Test(arguments: PushSignal.Kind.allCases.filter { $0 != .contactAccepted })
    func everySignalButAnAcceptanceFromAContactWakesTheUser(kind: PushSignal.Kind) {
        #expect(signal(kind, from: bob).mayWake(wakeList))
    }

    @Test(arguments: PushSignal.Kind.allCases.filter { $0 != .contactRequest })
    func nothingButAContactRequestFromAStrangerWakesTheUser(kind: PushSignal.Kind) {
        #expect(!signal(kind, from: mallory).mayWake(wakeList))
    }

    @Test func anAcceptanceFromSomebodyTheUserSentARequestToWakesTheUser() {
        #expect(signal(.contactAccepted, from: alice).mayWake(wakeList))
    }

    /// Only her answer is waited for: anything else of hers is from somebody not a contact.
    @Test(arguments: [
        PushSignal.Kind.message,
        .oneTimeExpense,
        .oneTimeExpenseChanged,
        .groupWelcome,
        .groupChanged,
        .groupMemberLeft,
        .groupExpense,
        .unknown,
    ])
    func nothingElseFromSomebodyTheUserSentARequestToWakesTheUser(kind: PushSignal.Kind) {
        #expect(!signal(kind, from: alice).mayWake(wakeList))
    }

    /// Sharing a group lets her through with what concerns it, an expense, a change and a leave.
    @Test(arguments: [PushSignal.Kind.groupExpense, .groupChanged, .groupMemberLeft])
    func whatConcernsAGroupWakesTheUserFromAContactOrAMember(kind: PushSignal.Kind) {
        #expect(signal(kind, from: bob).mayWake(wakeList))
        #expect(signal(kind, from: carol).mayWake(wakeList))
        #expect(!signal(kind, from: mallory).mayWake(wakeList))
        #expect(!signal(kind, from: nil).mayWake(wakeList))
    }

    /// And with nothing else of hers.
    @Test(arguments: PushSignal.Kind.allCases.filter {
        ![.groupExpense, .groupChanged, .groupMemberLeft, .contactRequest].contains($0)
    })
    func nothingButWhatConcernsAGroupFromAMemberWhoIsNoContactWakesTheUser(kind: PushSignal.Kind) {
        #expect(!signal(kind, from: carol).mayWake(wakeList))
    }

    @Test func anAcceptanceFromSomebodyTheUserSentNoRequestToDoesNotWakeTheUser() {
        #expect(!signal(.contactAccepted, from: mallory).mayWake(wakeList))
        #expect(!signal(.contactAccepted, from: bob).mayWake(wakeList))
    }

    /// A payload with no sender a build can read, or none at all.
    @Test(arguments: PushSignal.Kind.allCases.filter { $0 != .contactRequest })
    func aSignalNamingNobodyWakesTheUserOnlyAsAContactRequest(kind: PushSignal.Kind) {
        #expect(!signal(kind, from: nil).mayWake(wakeList))
    }

    /// A list never written, or one that cannot be read, lets nobody through but a request.
    @Test func nobodyIsOnAListNeverWritten() {
        #expect(!signal(.message, from: bob).mayWake(WakeListStorage(store: MemoryStore()).read()))
        #expect(signal(.contactRequest, from: bob).mayWake(WakeListStorage(store: MemoryStore()).read()))
    }

    private func signal(_ kind: PushSignal.Kind, from: String?) -> PushSignal {
        PushSignal(kind: kind, from: from)
    }
}
