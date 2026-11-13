import Foundation

/// A group's history's state, as Android's `GroupHistoryViewModel` holds it: read from the server
/// when the screen first shows, and again when the user asks once it could not be. Nothing of it is
/// kept.
@MainActor
final class GroupHistoryViewModel: ObservableObject {
    enum State: Equatable {
        case loading

        /// Nothing to show, for the error, until it is read again, see `retry`.
        case failed(GroupHistoryError)

        /// The versions to show, newest first.
        case loaded([GroupVersion])
    }

    @Published private(set) var state = State.loading

    let groupId: String

    private let callBacks: any GroupCallBacks
    private var isLoadStarted = false

    init(groupId: String, callBacks: any GroupCallBacks) {
        self.groupId = groupId
        self.callBacks = callBacks
    }

    /// Reads the history the first time the screen shows, and never again on coming back to it,
    /// from another app say. In a task of its own, which the screen leaving does not cut short.
    func load() {
        guard !isLoadStarted else {
            return
        }

        isLoadStarted = true
        read()
    }

    /// Reads the history again, once it could not be read.
    func retry() {
        guard case .failed = state else {
            return
        }

        read()
    }

    private func read() {
        state = .loading

        Task {
            switch await callBacks.history(groupId: groupId) {
            case .versions(let versions):
                state = .loaded(versions)
            case .unavailable(let error):
                state = .failed(error)
            }
        }
    }
}
