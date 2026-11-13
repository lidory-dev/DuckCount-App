import Foundation

/// The contact requests screen's state, as Android's `ContactRequestListViewModel` holds it.
@MainActor
final class ContactRequestListViewModel: ObservableObject {
    enum Answer: Equatable {
        case accept
        case refuse

        /// Refusing it, and blocking its sender.
        case block
    }

    /// The requests waiting for the user, oldest first, nil until read.
    @Published private(set) var requests: [ContactRequest]?

    /// The answer being sent for each request, by its id, until the server has taken it.
    @Published private(set) var answers: [String: Answer] = [:]

    /// Whether new requests may still turn up: a request only shows once a refresh opened it.
    @Published private(set) var isRefreshing = false

    /// Why the last answer could not be sent, until it is told.
    @Published var error: AnswerContactRequestError?

    private let callBacks: any ContactCallBacks
    private let requestsTask = TaskHandle()

    init(callBacks: any ContactCallBacks) {
        self.callBacks = callBacks

        follow(callBacks.contactRequests(), owner: self, into: requestsTask) { owner, requests in
            owner.requests = requests
        }
    }

    /// Whether there is nothing to show yet, rather than no request.
    var isLoading: Bool {
        guard let requests else {
            return true
        }

        return requests.isEmpty && isRefreshing
    }

    /// Opens the requests that arrived, which the screen asks for itself rather than relying on the
    /// contact list's.
    func refresh() async {
        isRefreshing = true
        await callBacks.refresh()
        isRefreshing = false
    }

    func accept(_ request: ContactRequest) {
        answer(request, with: .accept) { callBacks, id in await callBacks.acceptRequest(id: id) }
    }

    func refuse(_ request: ContactRequest) {
        answer(request, with: .refuse) { callBacks, id in await callBacks.refuseRequest(id: id) }
    }

    /// Blocks the request's sender without accepting it.
    func block(_ request: ContactRequest) {
        answer(request, with: .block) { callBacks, id in await callBacks.blockRequestSender(id: id) }
    }

    private func answer(
        _ request: ContactRequest,
        with answer: Answer,
        send: @escaping @MainActor (any ContactCallBacks, String) async -> AnswerContactRequestError?
    ) {
        // A request is answered once.
        guard answers[request.id] == nil else {
            return
        }

        answers[request.id] = answer

        Task {
            // A request answered leaves the list on its own.
            if let failure = await send(callBacks, request.id) {
                error = failure
            }

            answers[request.id] = nil
        }
    }
}
