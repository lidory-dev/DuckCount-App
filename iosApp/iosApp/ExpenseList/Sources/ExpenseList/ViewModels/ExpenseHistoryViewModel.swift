import Foundation

/// An expense's history's state, as Android's `ExpenseHistoryViewModel` holds it, which its
/// versions' own screens read too.
@MainActor
public final class ExpenseHistoryViewModel: ObservableObject {
    enum State: Equatable {
        case loading
        case failed(ExpenseHistoryError)
        case loaded([ExpenseVersion])
    }

    @Published private(set) var state = State.loading

    /// The names of everybody its versions name, as they are read.
    @Published private(set) var names = NameBook()

    let expenseId: String
    let selfUuid: String?

    private let callBacks: any ExpenseCallBacks
    private var nameFollower: NameFollower?
    private var isLoadStarted = false

    public init(expenseId: String, callBacks: any ExpenseCallBacks) {
        self.expenseId = expenseId
        self.callBacks = callBacks
        self.selfUuid = callBacks.authenticatedUserUuid()

        nameFollower = NameFollower { [weak self] names in
            self?.names = names
        }
    }

    /// Reads the history, once: it comes from the server.
    func load() async {
        guard !isLoadStarted else {
            return
        }

        isLoadStarted = true

        switch await callBacks.history(expenseId: expenseId) {
        case .unavailable(let error):
            state = .failed(error)

        case .versions(let versions):
            state = .loaded(versions)

            let people = versions.flatMap { version in
                [version.authorUuid, version.expense.creditorUuid] + version.expense.beneficiaries.map(\.userUuid)
            }

            nameFollower?.follow(Set(people), callBacks: callBacks)
        }
    }

    /// The beneficiaries of `version` as its screen lists them, as the expense's own screen does.
    func beneficiaries(of version: ExpenseVersion) -> [Beneficiary] {
        version.expense.beneficiaries.sortedByName(
            selfUuid: selfUuid,
            uuidOf: \.userUuid,
            nameOf: { self.names.name(of: $0.userUuid) }
        )
    }
}
