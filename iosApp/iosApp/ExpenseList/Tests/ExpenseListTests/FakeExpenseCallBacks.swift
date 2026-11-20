import Foundation

@testable import ExpenseList

let euro = Currency(id: "eur", code: "EUR", symbol: "€", hasSubunits: true)
let yen = Currency(id: "jpy", code: "JPY", symbol: "¥", hasSubunits: false)

let me = "me"
let ada = "ada"
let bob = "bob"
let cy = "cy"

let trip = "trip"

/// Answers as each test sets it, and records what the view models asked of it. Works the shares out
/// as the library would for what the tests look at: every fixed one as given, and the rest of the
/// amount split evenly in what the currency has for subunits, the first of them taking what that
/// leaves over.
@MainActor
final class FakeExpenseCallBacks: ExpenseCallBacks {
    let maxTitleLength = 50
    let maxDescriptionLength = 500
    var maxBeneficiaries = 100
    let maxGroupExpenseEdits = 10
    let currencies = [euro, yen]
    let defaultCurrency = euro

    var names: [String: String] = [me: "Me", ada: "Ada", bob: "Bob", cy: "Cy"]
    var users: [User] = [User(id: bob, fullName: "Bob"), User(id: ada, fullName: "Ada"), User(id: me, fullName: "Me")]

    /// The members of every group, those the device holds a name for.
    var groupMembers: [User] = [
        User(id: cy, fullName: "Cy"), User(id: bob, fullName: "Bob"), User(id: ada, fullName: "Ada"), User(id: me, fullName: "Me"),
    ]

    /// The currency of every group, nil once the device holds no such group.
    var heldGroupCurrency: Currency? = euro

    /// Answers an edit in place of `saveFailure` when set, as the server would.
    var onUpdate: ((Expense) -> ExpenseSaveError?)?
    var validationError: ExpenseValidationError?
    var saveFailure: ExpenseSaveError?
    var writeFailure: ExpenseWriteError?
    var history: ExpenseHistory = .versions([])

    /// What `expenses(limit:)` streams.
    let expenseList = FakeExpenseFeed()

    /// What `expense(id:)` streams.
    var storedExpenses: [String: Expense] = [:]

    /// What `groupExpenses(groupId:limit:)` streams.
    let groupExpenseList = FakeExpenseFeed()

    /// Whether the user is no longer in the group, as `isGroupLeft(groupId:)` streams it, see
    /// `setGroupLeft(_:)`.
    private(set) var isLeft = false
    private var groupLeftFollowers: [AsyncStream<Bool>.Continuation] = []

    private(set) var created: [Expense] = []
    private(set) var updated: [Expense] = []
    private(set) var deleted: [String] = []
    private(set) var settled: [(String, Bool)] = []
    private(set) var refreshCount = 0
    private(set) var restored: [String] = []
    private(set) var refreshedGroups: [String] = []

    /// The groups whose members were searched, one for each search.
    private(set) var searchedGroups: [String] = []

    /// The groups whose expenses were followed, one for each time.
    private(set) var followedGroups: [String] = []

    /// The groups whose currency was read, one for each time.
    private(set) var currencyGroups: [String] = []

    /// The groups whether the user is still in was followed, one for each time.
    private(set) var leftGroups: [String] = []

    /// Every follower of `fullNames(of:)`, with the people it follows, see `setName(_:for:)`.
    private var nameFollowers: [(userUuids: Set<String>, continuation: AsyncStream<[String: String]>.Continuation)] = []

    func currencyDisplayName(code: String) -> String {
        code
    }

    func authenticatedUserUuid() -> String? {
        me
    }

    /// What a new expense is dated, as the shared module would write it.
    var now = "2026-11-10T12:00:00.12"

    func currentTimestamp() -> String {
        now
    }

    func expenses(limit: Int) -> AsyncStream<[Expense]> {
        expenseList.stream(limit: limit)
    }

    func expense(id: String) -> AsyncStream<Expense?> {
        let expense = storedExpenses[id]

        return AsyncStream { $0.yield(expense) }
    }

    func refresh() async -> Bool {
        refreshCount += 1

        return true
    }

    func fullNames(of userUuids: Set<String>) -> AsyncStream<[String: String]> {
        let (stream, continuation) = AsyncStream<[String: String]>.makeStream()

        continuation.yield(names.filter { userUuids.contains($0.key) })
        nameFollowers.append((userUuids, continuation))

        return stream
    }

    /// Gives `userUuid` the name `name`, as a sync reading their profile does, which every screen
    /// following it is told.
    func setName(_ name: String, for userUuid: String) {
        names[userUuid] = name

        for follower in nameFollowers where follower.userUuids.contains(userUuid) {
            follower.continuation.yield(names.filter { follower.userUuids.contains($0.key) })
        }
    }

    func users(matching filter: String) -> AsyncStream<[User]> {
        let found = Self.matching(filter, in: users)

        return AsyncStream { $0.yield(found) }
    }

    /// Those of `people` whose name holds `filter`, taken without the whitespace around it, as the
    /// shared module searches.
    private static func matching(_ filter: String, in people: [User]) -> [User] {
        let trimmed = filter.trimmingCharacters(in: .whitespacesAndNewlines)

        return trimmed.isEmpty ? people : people.filter { $0.fullName.localizedCaseInsensitiveContains(trimmed) }
    }

    func computeBeneficiaries(
        amount: String,
        currency: Currency,
        beneficiaryUserUuids: [String],
        fixedShares: [String: String],
        sharePercentages: [String: String],
        shareWeights: [String: String]
    ) -> [Beneficiary] {
        let scale = currency.hasSubunits ? 2 : 0
        let fixedTotal = fixedShares.values.reduce(Decimal.zero) { $0 + $1.decimalValue }
        let split = beneficiaryUserUuids.filter { fixedShares[$0] == nil }
        let left = amount.decimalValue - fixedTotal

        var each = Decimal.zero
        var remainder = Decimal.zero

        if !split.isEmpty {
            var exact = left / Decimal(split.count)
            NSDecimalRound(&each, &exact, scale, .down)
            remainder = left - each * Decimal(split.count)
        }

        return beneficiaryUserUuids.map { uuid in
            if let fixed = fixedShares[uuid] {
                return Beneficiary(userUuid: uuid, share: fixed)
            }

            let share = uuid == split.first ? each + remainder : each

            return Beneficiary(userUuid: uuid, share: share.plainString(scale: scale))
        }
    }

    func validate(amount: String, currency: Currency, beneficiaries: [Beneficiary]) -> ExpenseValidationError? {
        if beneficiaries.isEmpty {
            return .noBeneficiaries
        }

        return validationError
    }

    func create(_ expense: Expense) async -> ExpenseSaveError? {
        created.append(expense)

        return saveFailure
    }

    func update(_ expense: Expense) async -> ExpenseSaveError? {
        updated.append(expense)

        return onUpdate.map { $0(expense) } ?? saveFailure
    }

    func delete(expenseId: String) async -> ExpenseWriteError? {
        deleted.append(expenseId)

        return writeFailure
    }

    func setSettled(expenseId: String, isSettled: Bool) async -> ExpenseWriteError? {
        settled.append((expenseId, isSettled))

        return writeFailure
    }

    func history(expenseId: String) async -> ExpenseHistory {
        history
    }

    func groupExpenses(groupId: String, limit: Int) -> AsyncStream<[Expense]> {
        followedGroups.append(groupId)

        return groupExpenseList.stream(limit: limit)
    }

    func refreshGroup(groupId: String) async {
        refreshedGroups.append(groupId)
    }

    func groupCurrency(groupId: String) -> AsyncStream<Currency?> {
        currencyGroups.append(groupId)

        let currency = heldGroupCurrency

        return AsyncStream { $0.yield(currency) }
    }

    func isGroupLeft(groupId: String) -> AsyncStream<Bool> {
        leftGroups.append(groupId)

        let (stream, continuation) = AsyncStream<Bool>.makeStream()

        continuation.yield(isLeft)
        groupLeftFollowers.append(continuation)

        return stream
    }

    /// Tells every screen following it that the user is in the group no more, or again.
    func setGroupLeft(_ isLeft: Bool) {
        self.isLeft = isLeft

        for follower in groupLeftFollowers {
            follower.yield(isLeft)
        }
    }

    func users(matching filter: String, inGroup groupId: String) -> AsyncStream<[User]> {
        searchedGroups.append(groupId)

        let found = Self.matching(filter, in: groupMembers)

        return AsyncStream { $0.yield(found) }
    }

    func restore(expenseId: String) async -> ExpenseWriteError? {
        restored.append(expenseId)

        return writeFailure
    }
}

/// A list of expenses the fake streams, to each follower the newest it asked for, as the shared
/// module's feeds do, see `ExpenseCallBacks.expenses(limit:)`.
@MainActor
final class FakeExpenseFeed {
    /// How many expenses each follower asked for, in order.
    private(set) var limits: [Int] = []

    private var expenses: [Expense]?
    private var followers: [(limit: Int, continuation: AsyncStream<[Expense]>.Continuation)] = []

    func stream(limit: Int) -> AsyncStream<[Expense]> {
        let (stream, continuation) = AsyncStream<[Expense]>.makeStream()

        limits.append(limit)
        followers.append((limit, continuation))

        if let expenses {
            continuation.yield(Array(expenses.prefix(limit)))
        }

        return stream
    }

    /// Streams `expenses`, newest first, to every follower.
    func send(_ expenses: [Expense]) {
        self.expenses = expenses

        for follower in followers {
            follower.continuation.yield(Array(expenses.prefix(follower.limit)))
        }
    }
}

/// Waits for what a view model started in a task to settle.
@MainActor
func settle(until isDone: () -> Bool) async {
    for _ in 0..<200 where !isDone() {
        await Task.yield()
    }
}

func expense(
    id: String = "expense",
    timestamp: String = "2026-11-10T12:00:00",
    title: String = "Lunch",
    amount: String = "20.00",
    creditorUuid: String = me,
    beneficiaries: [Beneficiary] = [Beneficiary(userUuid: me, share: "10.00"), Beneficiary(userUuid: ada, share: "10.00")],
    revision: Int64 = 1,
    groupId: String? = nil,
    isDeleted: Bool = false,
    deletedByUuid: String? = nil
) -> Expense {
    Expense(
        id: id,
        timestamp: timestamp,
        title: title,
        description: "",
        amount: amount,
        creditorUuid: creditorUuid,
        currency: euro,
        beneficiaries: beneficiaries,
        revision: revision,
        groupId: groupId,
        isDeleted: isDeleted,
        deletedByUuid: deletedByUuid
    )
}

/// Paid by Bob, its creditor, to the user, its one beneficiary, in the group `trip`.
func settlement(id: String = "settlement", timestamp: String = "2026-11-10T12:00:00", isDeleted: Bool = false) -> Expense {
    Expense(
        id: id,
        timestamp: timestamp,
        title: "",
        description: "",
        amount: "20.00",
        creditorUuid: bob,
        currency: euro,
        beneficiaries: [Beneficiary(userUuid: me, share: "20.00", isShareFixed: true)],
        revision: 1,
        groupId: trip,
        isSettlement: true,
        isDeleted: isDeleted,
        deletedByUuid: isDeleted ? me : nil
    )
}
