import Combine

@MainActor
public protocol ContactListState: ObservableObject {
    var contacts: [Contact] { get }
    var isLoading: Bool { get }
    var searchQuery: String { get set }
}
