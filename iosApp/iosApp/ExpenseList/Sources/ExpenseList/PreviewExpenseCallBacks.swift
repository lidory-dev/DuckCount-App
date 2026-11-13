import Foundation

/// Sample expenses and people for the previews, which reach no server.
@MainActor
final class PreviewExpenseCallBacks: ExpenseCallBacks {
    let maxTitleLength = 50
    let maxDescriptionLength = 500
    let maxBeneficiaries = 100
    let maxGroupExpenseEdits = 10

    let currencies = [
        Currency(id: "eur", code: "EUR", symbol: "€", hasSubunits: true),
        Currency(id: "usd", code: "USD", symbol: "$", hasSubunits: true),
        Currency(id: "jpy", code: "JPY", symbol: "¥", hasSubunits: false),
    ]

    var defaultCurrency: Currency {
        currencies[0]
    }

    private let people = [
        User(id: "me", fullName: "Camille Martin"),
        User(id: "alice", fullName: "Alice Durand"),
        User(id: "bob", fullName: "Bob Leroy"),
    ]

    private var sampleExpenses: [Expense] {
        [
            Expense(
                id: "1",
                timestamp: "2026-11-10T12:30:00",
                title: "Lunch",
                description: "At the market",
                amount: "36.00",
                creditorUuid: "me",
                currency: defaultCurrency,
                beneficiaries: [
                    Beneficiary(userUuid: "me", share: "12.00"),
                    Beneficiary(userUuid: "alice", share: "12.00"),
                    Beneficiary(userUuid: "bob", share: "12.00"),
                ]
            ),
            Expense(
                id: "2",
                timestamp: "2026-11-08T20:15:00",
                title: "Cinema",
                description: "",
                amount: "24.00",
                creditorUuid: "alice",
                currency: defaultCurrency,
                beneficiaries: [
                    Beneficiary(userUuid: "me", share: "12.00"),
                    Beneficiary(userUuid: "alice", share: "12.00"),
                ],
                isSettled: true,
                settledAt: "2026-11-09T09:00:00"
            ),
        ]
    }

    /// A group's expenses: one at its last revision, one deleted, and a settlement.
    private var sampleGroupExpenses: [Expense] {
        [
            Expense(
                id: "g1",
                timestamp: "2026-11-10T19:00:00",
                title: "Groceries",
                description: "",
                amount: "45.00",
                creditorUuid: "alice",
                currency: defaultCurrency,
                beneficiaries: [
                    Beneficiary(userUuid: "me", share: "15.00"),
                    Beneficiary(userUuid: "alice", share: "15.00"),
                    Beneficiary(userUuid: "bob", share: "15.00"),
                ],
                revision: 11,
                groupId: "trip"
            ),
            Expense(
                id: "g2",
                timestamp: "2026-11-10T09:30:00",
                title: "Train tickets",
                description: "Paris to Lyon",
                amount: "80.00",
                creditorUuid: "me",
                currency: defaultCurrency,
                beneficiaries: [
                    Beneficiary(userUuid: "me", share: "40.00"),
                    Beneficiary(userUuid: "bob", share: "40.00"),
                ],
                revision: 2,
                groupId: "trip",
                isDeleted: true,
                deletedByUuid: "bob"
            ),
            Expense(
                id: "g3",
                timestamp: "2026-11-09T18:00:00",
                title: "",
                description: "",
                amount: "15.00",
                creditorUuid: "bob",
                currency: defaultCurrency,
                beneficiaries: [Beneficiary(userUuid: "me", share: "15.00", isShareFixed: true)],
                revision: 1,
                groupId: "trip",
                isSettlement: true
            ),
        ]
    }

    func currencyDisplayName(code: String) -> String {
        Locale.current.localizedString(forCurrencyCode: code) ?? code
    }

    func authenticatedUserUuid() -> String? {
        "me"
    }

    func currentTimestamp() -> String {
        "2026-11-10T12:00:00"
    }

    func expenses() -> AsyncStream<[Expense]> {
        AsyncStream { $0.yield(sampleExpenses) }
    }

    func expense(id: String) -> AsyncStream<Expense?> {
        let expense = (sampleExpenses + sampleGroupExpenses).first { $0.id == id } ?? sampleExpenses[0]

        return AsyncStream { $0.yield(expense) }
    }

    func refresh() async -> Bool {
        true
    }

    func fullNames(of userUuids: Set<String>) -> AsyncStream<[String: String]> {
        let names = Dictionary(uniqueKeysWithValues: people.filter { userUuids.contains($0.id) }.map { ($0.id, $0.fullName) })

        return AsyncStream { $0.yield(names) }
    }

    func users(matching filter: String) -> AsyncStream<[User]> {
        let users = filter.isEmpty ? people : people.filter { $0.fullName.localizedCaseInsensitiveContains(filter) }

        return AsyncStream { $0.yield(users) }
    }

    func computeBeneficiaries(
        amount: String,
        currency: Currency,
        beneficiaryUserUuids: [String],
        fixedShares: [String: String],
        sharePercentages: [String: String],
        shareWeights: [String: String]
    ) -> [Beneficiary] {
        let share = beneficiaryUserUuids.isEmpty ? Decimal.zero : amount.decimalValue / Decimal(beneficiaryUserUuids.count)

        return beneficiaryUserUuids.map { uuid in
            Beneficiary(userUuid: uuid, share: fixedShares[uuid] ?? share.plainString(scale: currency.hasSubunits ? 2 : 0))
        }
    }

    func validate(amount: String, currency: Currency, beneficiaries: [Beneficiary]) -> ExpenseValidationError? {
        beneficiaries.isEmpty ? .noBeneficiaries : nil
    }

    func create(_ expense: Expense) async -> ExpenseSaveError? {
        nil
    }

    func update(_ expense: Expense) async -> ExpenseSaveError? {
        nil
    }

    func delete(expenseId: String) async -> ExpenseWriteError? {
        nil
    }

    func setSettled(expenseId: String, isSettled: Bool) async -> ExpenseWriteError? {
        nil
    }

    func history(expenseId: String) async -> ExpenseHistory {
        .versions(sampleExpenses.enumerated().map { index, expense in ExpenseVersion(revision: Int64(index + 1), expense: expense, authorUuid: expense.creditorUuid, isCreation: index == 0, madeAt: expense.timestamp) })
    }

    func groupExpenses(groupId: String) -> AsyncStream<[Expense]> {
        AsyncStream { $0.yield(sampleGroupExpenses) }
    }

    func refreshGroup(groupId: String) async {}

    func groupCurrency(groupId: String) -> AsyncStream<Currency?> {
        AsyncStream { $0.yield(defaultCurrency) }
    }

    func isGroupLeft(groupId: String) -> AsyncStream<Bool> {
        AsyncStream { $0.yield(false) }
    }

    func users(matching filter: String, inGroup groupId: String) -> AsyncStream<[User]> {
        users(matching: filter)
    }

    func restore(expenseId: String) async -> ExpenseWriteError? {
        nil
    }
}
