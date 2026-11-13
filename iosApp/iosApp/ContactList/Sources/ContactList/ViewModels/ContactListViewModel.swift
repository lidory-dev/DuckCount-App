import Foundation

/// The contacts tab's state, as Android's `ContactListViewModel` holds it.
@MainActor
public final class ContactListViewModel: ObservableObject {
    enum State: Equatable {
        /// Still reading what the device holds: neither the list nor the reservation prompt.
        case loading

        /// The user has not reserved a username yet, so nobody can add them, nor they anybody: the
        /// list would stay empty whatever they do, so it asks for one instead.
        case usernameReservationRequired

        case content(Content)
    }

    /// The contacts whose name holds what is searched, by letter, whether the user has any at all
    /// whatever is searched, so that a search that finds nobody is told apart from a user with
    /// nobody to find, and how many contact requests are waiting and people are blocked.
    struct Content: Equatable {
        let sections: [ContactSection]
        let hasContacts: Bool
        let contactRequestCount: Int
        let blockedUserCount: Int
    }

    @Published private(set) var state = State.loading

    /// The search field's text, as typed: the shared module trims it.
    @Published private(set) var searchQuery = ""

    private let callBacks: any ContactCallBacks
    private let selfUuid: String?

    private var hasReadReservedUsername = false
    private var reservedUsername: String?
    private var found: [Contact]?
    private var hasContacts: Bool?
    private var contactRequestCount: Int?
    private var blockedUserCount: Int?

    private let reservedUsernameTask = TaskHandle()
    private let foundTask = TaskHandle()
    private let allTask = TaskHandle()
    private let countTask = TaskHandle()
    private let blockedTask = TaskHandle()

    public init(callBacks: any ContactCallBacks) {
        self.callBacks = callBacks
        self.selfUuid = callBacks.authenticatedUserUuid()

        follow(callBacks.reservedUsername(), owner: self, into: reservedUsernameTask) { owner, username in
            owner.hasReadReservedUsername = true
            owner.reservedUsername = username
            owner.update()
        }

        // Whatever is searched, to tell a search that found nobody from a user with nobody to find.
        follow(callBacks.contacts(matching: ""), owner: self, into: allTask) { owner, contacts in
            owner.hasContacts = contacts.contains { $0.id != owner.selfUuid }
            owner.update()
        }

        follow(callBacks.contactRequestCount(), owner: self, into: countTask) { owner, count in
            owner.contactRequestCount = count
            owner.update()
        }

        follow(callBacks.blockedUsers(), owner: self, into: blockedTask) { owner, users in
            owner.blockedUserCount = users.count
            owner.update()
        }

        searchContacts()
    }

    func search(_ query: String) {
        guard query != searchQuery else {
            return
        }

        searchQuery = query
        searchContacts()
    }

    /// Brings the contacts and the requests up to date, which the list shows as they are stored.
    func refresh() async {
        await callBacks.refresh()
    }

    private func searchContacts() {
        follow(callBacks.contacts(matching: searchQuery), owner: self, into: foundTask) { owner, contacts in
            // The user is listed by the shared module as anybody this device holds a name for.
            owner.found = contacts.filter { $0.id != owner.selfUuid }
            owner.update()
        }
    }

    private func update() {
        guard
            hasReadReservedUsername,
            let found,
            let hasContacts,
            let contactRequestCount,
            let blockedUserCount
        else {
            state = .loading
            return
        }

        guard reservedUsername != nil else {
            state = .usernameReservationRequired
            return
        }

        state = .content(
            Content(
                sections: ContactSection.grouping(found),
                hasContacts: hasContacts,
                contactRequestCount: contactRequestCount,
                blockedUserCount: blockedUserCount
            )
        )
    }
}
