import Foundation
import Testing

@testable import ContactList

/// What the contacts tab knows when it lists nobody, as Android's `ContactListEmptyTest` says it:
/// that a search found nobody, or that the user has nobody to find, whatever they search.
@MainActor
struct ContactListViewModelTests {
    @Test func aSearchThatFindsNobodyIsToldApartFromHavingNoContacts() async {
        let viewModel = ContactListViewModel(callBacks: FakeContactCallBacks())

        viewModel.search("ffsef")
        let content = await content(of: viewModel) { $0.sections.isEmpty }

        #expect(content?.sections == [])
        #expect(content?.hasContacts == true)
    }

    /// Listed by the same query as the contacts, the user is no contact of their own.
    @Test func aUserWithNobodyButThemselfHasNoContacts() async {
        let callBacks = FakeContactCallBacks()
        callBacks.people = [Contact(id: me, fullName: "Me")]
        let viewModel = ContactListViewModel(callBacks: callBacks)

        let listed = await content(of: viewModel)

        #expect(listed?.hasContacts == false)
        #expect(listed?.sections == [])

        viewModel.search("ffsef")
        let searched = await content(of: viewModel)

        #expect(searched?.hasContacts == false)
    }

    @Test func aContactFoundIsListedAndTheUserIsNot() async {
        let viewModel = ContactListViewModel(callBacks: FakeContactCallBacks())

        let listed = await content(of: viewModel)

        #expect(listed?.sections.map(\.id) == ["A", "B"])

        viewModel.search("bo")
        let searched = await content(of: viewModel) { $0.sections.count == 1 }

        #expect(searched?.sections == [ContactSection(id: "B", contacts: [Contact(id: bob, fullName: "Bob")])])
        #expect(searched?.hasContacts == true)
    }

    /// Offered at the end of the list, whatever it lists.
    @Test func howManyPeopleTheUserBlockedIsToldWhateverIsSearched() async {
        let callBacks = FakeContactCallBacks()
        callBacks.blocked = [BlockedUser(id: "mallory", fullName: "Mallory"), BlockedUser(id: "trent", fullName: nil)]
        let viewModel = ContactListViewModel(callBacks: callBacks)

        let listed = await content(of: viewModel)

        #expect(listed?.blockedUserCount == 2)

        viewModel.search("ffsef")
        let searched = await content(of: viewModel) { $0.sections.isEmpty }

        #expect(searched?.blockedUserCount == 2)
    }

    @Test func howManyRequestsAreWaitingIsTold() async {
        let callBacks = FakeContactCallBacks()
        callBacks.requestCount = 3
        let viewModel = ContactListViewModel(callBacks: callBacks)

        let listed = await content(of: viewModel)

        #expect(listed?.contactRequestCount == 3)
    }

    /// Nobody can add a user with no username, nor they anybody.
    @Test func aUserWithNoUsernameIsAskedToReserveOne() async {
        let callBacks = FakeContactCallBacks()
        callBacks.reserved = nil
        let viewModel = ContactListViewModel(callBacks: callBacks)

        await settle { viewModel.state != .loading }

        #expect(viewModel.state == .usernameReservationRequired)
    }

    @Test func aRefreshAsksTheSharedModule() async {
        let callBacks = FakeContactCallBacks()
        let viewModel = ContactListViewModel(callBacks: callBacks)

        await viewModel.refresh()

        #expect(callBacks.refreshCount == 1)
    }

    /// The list once read, and once `isDone` holds of it.
    private func content(
        of viewModel: ContactListViewModel,
        until isDone: @escaping (ContactListViewModel.Content) -> Bool = { _ in true }
    ) async -> ContactListViewModel.Content? {
        func read() -> ContactListViewModel.Content? {
            guard case .content(let content) = viewModel.state, isDone(content) else {
                return nil
            }

            return content
        }

        await settle { read() != nil }

        return read()
    }
}

/// How the list groups contacts by letter, as Android's contact list does.
struct ContactSectionTests {
    @Test func contactsAreListedUnderTheirFirstLetterWithoutItsAccent() {
        let sections = ContactSection.grouping([
            Contact(id: "1", fullName: "Émile"),
            Contact(id: "2", fullName: "bob"),
            Contact(id: "3", fullName: "Eve"),
            Contact(id: "4", fullName: "Ada"),
        ])

        #expect(sections.map(\.id) == ["A", "B", "E"])
        #expect(sections[2].contacts.map(\.fullName) == ["Émile", "Eve"])
    }

    @Test func namesThatStartWithNoLetterAreListedLast() {
        let sections = ContactSection.grouping([
            Contact(id: "1", fullName: "42"),
            Contact(id: "2", fullName: "Zoé"),
            Contact(id: "3", fullName: ""),
            Contact(id: "4", fullName: "🦆 Duck"),
        ])

        #expect(sections.map(\.id) == ["Z", ContactSection.other])
        #expect(Set(sections[1].contacts.map(\.id)) == ["1", "3", "4"])
    }

    @Test func twoOfTheSameNameKeepTheOrderTheyCameIn() {
        let sections = ContactSection.grouping([
            Contact(id: "2", fullName: "Ada"),
            Contact(id: "1", fullName: "Ada"),
        ])

        #expect(sections.first?.contacts.map(\.id) == ["2", "1"])
    }

    @Test func aLetterOfAnotherScriptHasASectionOfItsOwn() {
        #expect(ContactSection.sectionKey(of: "Ωmega") == "Ω")
        #expect(ContactSection.sectionKey(of: "ålesund") == "A")
    }
}

/// The username the form holds once an edit is taken, as Android's `NewContactUsernameTest` says
/// it: as typed, so that the field never hands the keyboard a new text, and checked as sent.
@MainActor
struct NewContactViewModelTests {
    /// Checked lowercased, which the rules refuse a capital in.
    @Test func aUsernameTypedWithCapitalsIsHeldAsTypedAndCheckedLowercased() {
        let viewModel = NewContactViewModel(callBacks: FakeContactCallBacks())

        viewModel.updateUsername("Alice")

        #expect(viewModel.username == "Alice")
        #expect(viewModel.trimmedUsername == "alice")
        #expect(viewModel.inputError == nil)
        #expect(viewModel.canSend)
    }

    /// Trimmed, the space would be swallowed, and the cursor moved back over it.
    @Test func aSpaceTypedAtTheEndIsHeldAsTyped() {
        let viewModel = NewContactViewModel(callBacks: FakeContactCallBacks())

        viewModel.updateUsername("alice ")

        #expect(viewModel.username == "alice ")
        #expect(viewModel.trimmedUsername == "alice")
    }

    @Test func nothingIsSentForAUsernameNobodyCanHave() async {
        let callBacks = FakeContactCallBacks()
        let viewModel = NewContactViewModel(callBacks: callBacks)

        viewModel.updateUsername("4lice")
        viewModel.send()
        viewModel.updateUsername("   ")
        viewModel.send()
        await settle { false }

        #expect(callBacks.sentTo.isEmpty)
    }

    @Test func aSentRequestEmptiesTheFieldAndIsConfirmedOnce() async {
        let callBacks = FakeContactCallBacks()
        let viewModel = NewContactViewModel(callBacks: callBacks)

        viewModel.updateUsername(" Alice")
        viewModel.send()
        await settle { !viewModel.isSending }

        #expect(callBacks.sentTo == ["alice"])
        #expect(viewModel.username == "")
        #expect(viewModel.sentTo == "alice")

        viewModel.confirmSent(to: "bob")
        #expect(viewModel.sentTo == "alice")

        viewModel.confirmSent(to: "alice")
        #expect(viewModel.sentTo == nil)
    }

    @Test func aRequestIsNotSentTwiceWhileOneIsUnderWay() async {
        let callBacks = FakeContactCallBacks()
        let gate = Gate()
        callBacks.gate = gate
        let viewModel = NewContactViewModel(callBacks: callBacks)

        viewModel.updateUsername("alice")
        viewModel.send()
        await settle { callBacks.sentTo.count == 1 }
        viewModel.send()
        gate.open()
        await settle { !viewModel.isSending }

        #expect(callBacks.sentTo == ["alice"])
    }

    /// Kept for the user to read, and cleared once they type something else.
    @Test func aFailureIsKeptUntilTheUsernameChanges() async {
        let callBacks = FakeContactCallBacks()
        callBacks.addFailure = .notFound
        let viewModel = NewContactViewModel(callBacks: callBacks)

        viewModel.updateUsername("alice")
        viewModel.send()
        await settle { !viewModel.isSending && viewModel.error != nil }

        #expect(viewModel.error == .notFound)
        #expect(viewModel.username == "alice")
        #expect(viewModel.sentTo == nil)

        viewModel.updateUsername("alicia")

        #expect(viewModel.error == nil)
    }
}

/// The contact requests screen.
@MainActor
struct ContactRequestListViewModelTests {
    private let request = ContactRequest(id: "request", senderUserUuid: ada, senderFullName: "Ada")

    /// A request answered leaves the list on its own: nothing would answer it a second time.
    @Test func aRequestIsAnsweredOnce() async {
        let callBacks = FakeContactCallBacks()
        let gate = Gate()
        callBacks.gate = gate
        let viewModel = ContactRequestListViewModel(callBacks: callBacks)

        viewModel.accept(request)
        viewModel.refuse(request)
        viewModel.block(request)
        await settle { callBacks.accepted.count == 1 }

        #expect(viewModel.answers == [request.id: .accept])

        gate.open()
        await settle { viewModel.answers.isEmpty }

        #expect(callBacks.accepted == [request.id])
        #expect(callBacks.refused.isEmpty)
        #expect(callBacks.blockedSenders.isEmpty)
        #expect(viewModel.error == nil)
    }

    @Test func aFailedAnswerIsToldAndCanBeSentAgain() async {
        let callBacks = FakeContactCallBacks()
        callBacks.answerFailure = .noInternet
        let viewModel = ContactRequestListViewModel(callBacks: callBacks)

        viewModel.block(request)
        await settle { viewModel.error != nil && viewModel.answers.isEmpty }

        #expect(viewModel.error == .noInternet)

        viewModel.refuse(request)
        await settle { callBacks.refused.count == 1 && viewModel.answers.isEmpty }

        #expect(callBacks.blockedSenders == [request.id])
        #expect(callBacks.refused == [request.id])
    }

    /// No request is shown as none while a refresh may still bring one.
    @Test func noRequestIsLoadingWhileARefreshIsUnderWay() async {
        let callBacks = FakeContactCallBacks()
        let gate = Gate()
        callBacks.gate = gate
        let viewModel = ContactRequestListViewModel(callBacks: callBacks)

        #expect(viewModel.isLoading)

        await settle { viewModel.requests != nil }
        let refresh = Task { await viewModel.refresh() }
        await settle { viewModel.isRefreshing }

        #expect(viewModel.isLoading)

        gate.open()
        await refresh.value

        #expect(!viewModel.isLoading)
        #expect(viewModel.requests == [])
    }
}

/// A contact's screen.
@MainActor
struct ContactDetailViewModelTests {
    @Test func aRemovedContactLeavesNothingToShow() async {
        let callBacks = FakeContactCallBacks()
        let viewModel = ContactDetailViewModel(contactId: ada, callBacks: callBacks)

        viewModel.remove()
        await settle { viewModel.isRemoved }

        #expect(callBacks.removed == [ada])
        #expect(viewModel.isRemoved)
        #expect(viewModel.error == nil)
    }

    /// Blocking removes the contact first, so nothing else is started meanwhile.
    @Test func nothingElseIsDoneWhileTheContactIsBlocked() async {
        let callBacks = FakeContactCallBacks()
        let gate = Gate()
        callBacks.gate = gate
        let viewModel = ContactDetailViewModel(contactId: ada, callBacks: callBacks)

        viewModel.block()
        await settle { callBacks.blockedContacts.count == 1 }
        viewModel.remove()
        viewModel.block()
        gate.open()
        await settle { viewModel.isRemoved }

        #expect(callBacks.blockedContacts == [ada])
        #expect(callBacks.removed.isEmpty)
    }

    @Test func aFailedBlockingIsToldAsABlocking() async {
        let callBacks = FakeContactCallBacks()
        callBacks.blockFailure = .rateLimited
        let viewModel = ContactDetailViewModel(contactId: ada, callBacks: callBacks)

        viewModel.block()
        await settle { viewModel.error != nil }

        #expect(viewModel.error == .blocking(.rateLimited))
        #expect(!viewModel.isRemoved)
        #expect(!viewModel.isBusy)
    }

    /// Only a contact has an identity to check, and is blocked from here; neither is offered until
    /// that is known.
    @Test func onlyAContactIsOfferedTheirSafetyNumberAndABlock() async {
        let callBacks = FakeContactCallBacks()
        callBacks.verifications = [
            ada: .available(safetyNumber: "1234", isVerified: false),
            bob: .notContact,
        ]
        let contact = ContactDetailViewModel(contactId: ada, callBacks: callBacks)
        let stranger = ContactDetailViewModel(contactId: bob, callBacks: callBacks)
        let unknown = ContactDetailViewModel(contactId: "carol", callBacks: callBacks)

        await settle { contact.verification != nil && stranger.verification != nil }

        #expect(contact.isContact)
        #expect(!stranger.isContact)
        #expect(!unknown.isContact)
    }
}

/// The safety number screen.
@MainActor
struct ContactVerificationViewModelTests {
    /// The number the user read, which is the one they vouch for.
    @Test func theNumberShownIsTheOneMarkedVerified() async {
        let callBacks = FakeContactCallBacks()
        callBacks.verifications = [ada: .available(safetyNumber: "1234 5678", isVerified: false)]
        let viewModel = ContactVerificationViewModel(contactId: ada, callBacks: callBacks)

        await settle { viewModel.verification != nil && viewModel.fullName != nil }
        viewModel.toggleVerified()
        await settle { !viewModel.isUpdating && !callBacks.marked.isEmpty }

        #expect(callBacks.marked.map(\.0) == [ada])
        #expect(callBacks.marked.map(\.1) == ["1234 5678"])
        #expect(callBacks.cleared.isEmpty)
        #expect(viewModel.fullName == "Ada")
    }

    @Test func aVerifiedContactHasTheMarkCleared() async {
        let callBacks = FakeContactCallBacks()
        callBacks.verifications = [ada: .available(safetyNumber: "1234", isVerified: true)]
        let viewModel = ContactVerificationViewModel(contactId: ada, callBacks: callBacks)

        await settle { viewModel.verification != nil }
        viewModel.toggleVerified()
        await settle { !viewModel.isUpdating && !callBacks.cleared.isEmpty }

        #expect(callBacks.cleared == [ada])
        #expect(callBacks.marked.isEmpty)
    }

    /// An identity that changed has no number to vouch for until the contact is checked again.
    @Test func nothingIsMarkedWithoutANumber() async {
        let callBacks = FakeContactCallBacks()
        callBacks.verifications = [ada: .identityChanged]
        let viewModel = ContactVerificationViewModel(contactId: ada, callBacks: callBacks)

        await settle { viewModel.verification != nil }
        viewModel.toggleVerified()
        await settle { false }

        #expect(callBacks.marked.isEmpty)
        #expect(callBacks.cleared.isEmpty)
    }

    @Test func aContactWithNoNameHasNoneShown() async {
        let callBacks = FakeContactCallBacks()
        callBacks.people = [Contact(id: ada, fullName: "")]
        callBacks.verifications = [ada: .available(safetyNumber: "1234", isVerified: false)]
        let viewModel = ContactVerificationViewModel(contactId: ada, callBacks: callBacks)

        await settle { viewModel.verification != nil }
        await settle { false }

        #expect(viewModel.fullName == nil)
    }
}

/// The blocked users screen.
@MainActor
struct BlockedUserListViewModelTests {
    @Test func blockedUsersAreListedByNameTheNamelessLast() async {
        let callBacks = FakeContactCallBacks()
        callBacks.blocked = [
            BlockedUser(id: "trent", fullName: nil),
            BlockedUser(id: "mallory", fullName: "Mallory"),
            BlockedUser(id: "eve", fullName: "Eve"),
        ]
        let viewModel = BlockedUserListViewModel(callBacks: callBacks)

        await settle { viewModel.users != nil }

        #expect(viewModel.users?.map(\.id) == ["eve", "mallory", "trent"])
    }

    @Test func anUnblockIsSentForTheUserTapped() async {
        let callBacks = FakeContactCallBacks()
        let viewModel = BlockedUserListViewModel(callBacks: callBacks)

        viewModel.unblock(BlockedUser(id: "mallory", fullName: "Mallory"))
        await settle { !callBacks.unblocked.isEmpty }

        #expect(callBacks.unblocked == ["mallory"])
    }
}
