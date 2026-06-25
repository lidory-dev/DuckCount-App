import ContactList
import Foundation
import Shared

@MainActor
class ContactListViewModel: ObservableObject, ContactListState {
    @Published var contacts: [Contact] = []
    @Published var isLoading: Bool = true
    @Published var searchQuery: String = "" {
        didSet {
            observeContacts()
        }
    }

    // TODO: Replace with the actual logged-in user UUID once authentication is implemented.
    static let currentUserUuid = "00000000-0000-0000-0000-000000000001"

    private let userRepository: Shared.UserRepository
    private var cancellable: Cancellable?

    init() {
        let helper = KoinHelper()
        self.userRepository = helper.getUserRepository()

        observeContacts()
    }

    deinit {
        cancellable?.cancel()
    }

    private func observeContacts() {
        cancellable?.cancel()
        let flow = userRepository.getAllFiltered(filter: searchQuery)

        cancellable = FlowCollectorKt.collect(
            flow,
            onEach: { [weak self] value in
                guard let self else {
                    return
                }
                guard let kotlinUsers = value as? [Shared.User] else {
                    return
                }

                // Exclude the current user from their own contact list, matching Android.
                self.contacts = kotlinUsers
                    .filter { $0.uuid != Self.currentUserUuid }
                    .map { kotlinUser in
                        Contact(
                            id: kotlinUser.uuid,
                            username: kotlinUser.username,
                            fullName: kotlinUser.fullName
                        )
                    }
                self.isLoading = false
            }, onCompletion: { _ in })
    }
}
