import Foundation

@testable import ExpenseList

let euro = Currency(id: "eur", code: "EUR", symbol: "€", hasSubunits: true)
let yen = Currency(id: "jpy", code: "JPY", symbol: "¥", hasSubunits: false)

let me = "me"
let ada = "ada"
let bob = "bob"

/// Answers as each test sets it, and records what the view models asked of it. Works the shares out
/// as the library would for what the tests look at: every fixed one as given, and the rest of the
/// amount split evenly in what the currency has for subunits, the first of them taking what that
/// leaves over.
@MainActor
final class FakeExpenseCallBacks: ExpenseCallBacks {
    let maxTitleLength = 50
    let maxDescriptionLength = 500
    var maxBeneficiaries = 100
    let currencies = [euro, yen]
    let defaultCurrency = euro

    var names: [String: String] = [me: "Me", ada: "Ada", bob: "Bob"]
    var users: [User] = [User(id: bob, fullName: "Bob"), User(id: ada, fullName: "Ada"), User(id: me, fullName: "Me")]
    var validationError: ExpenseValidationError?
    var saveFailure: ExpenseSaveError?
    var writeFailure: ExpenseWriteError?
    var history: ExpenseHistory = .versions([])

    /// What `expenses()` and `expense(id:)` stream.
    let expenseList = AsyncStream<[Expense]>.makeStream()
    var storedExpenses: [String: Expense] = [:]

    private(set) var created: [Expense] = []
    private(set) var updated: [Expense] = []
    private(set) var deleted: [String] = []
    private(set) var settled: [(String, Bool)] = []
    private(set) var refreshCount = 0

    func currencyDisplayName(code: String) -> String {
        code
    }

    func authenticatedUserUuid() -> String? {
        me
    }

    func expenses() -> AsyncStream<[Expense]> {
        expenseList.stream
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
        let known = names.filter { userUuids.contains($0.key) }

        return AsyncStream { $0.yield(known) }
    }

    func users(matching filter: String) -> AsyncStream<[User]> {
        let found = filter.isEmpty ? users : users.filter { $0.fullName.localizedCaseInsensitiveContains(filter) }

        return AsyncStream { $0.yield(found) }
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

        return saveFailure
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
    beneficiaries: [Beneficiary] = [Beneficiary(userUuid: me, share: "10.00"), Beneficiary(userUuid: ada, share: "10.00")]
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
        revision: 1
    )
}
