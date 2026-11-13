import Foundation

/// What the expense screens need from the rest of the app, which implements it on the shared
/// module, as Android's `ExpenseListCallBacks`, `ExpenseDetailCallBacks`, `CreateExpenseCallBacks`
/// and `ExpenseHistoryCallBacks` are implemented in its app module: the screens hold no business
/// rule of their own, the library working out and checking the shares, and this package knows
/// nothing of `Shared`.
///
/// Each stream emits what the device shows as it changes, and stops being followed once the
/// screen reading it is gone.
@MainActor
public protocol ExpenseCallBacks: AnyObject {
    /// Longest title an expense takes, in UTF-16 units, without the whitespace around it.
    var maxTitleLength: Int { get }

    /// Longest description an expense takes, counted as the title is.
    var maxDescriptionLength: Int { get }

    /// The most beneficiaries an expense may list, which the server enforces too: the form never
    /// selects more.
    var maxBeneficiaries: Int { get }

    /// The currencies an expense can be created with.
    var currencies: [Currency] { get }

    /// The currency a new expense starts in.
    var defaultCurrency: Currency { get }

    /// The localized name of the currency with the ISO 4217 `code`, "US Dollar" for "USD".
    func currencyDisplayName(code: String) -> String

    /// The user's own UUID, whom every list of people names first.
    func authenticatedUserUuid() -> String?

    /// The one-time expenses, newest first.
    func expenses() -> AsyncStream<[Expense]>

    /// The expense `id`, or nil once it is gone.
    func expense(id: String) -> AsyncStream<Expense?>

    /// Brings the expenses up to date with the server, and says whether it could.
    func refresh() async -> Bool

    /// The name to show for each of `userUuids` that the device holds one for: one missing is
    /// somebody it holds no profile for, which a one-time expense makes ordinary, the server asking
    /// for no relationship between a creditor and the people they name.
    func fullNames(of userUuids: Set<String>) -> AsyncStream<[String: String]>

    /// Who an expense can name, whose name contains `filter`.
    func users(matching filter: String) -> AsyncStream<[User]>

    /// The shares of `beneficiaryUserUuids` of `amount`, as the library works them out from the
    /// shares fixed as amounts, percentages and weights, in the order given. Each comes back
    /// unfixed: the form says which it fixed.
    func computeBeneficiaries(
        amount: String,
        currency: Currency,
        beneficiaryUserUuids: [String],
        fixedShares: [String: String],
        sharePercentages: [String: String],
        shareWeights: [String: String]
    ) -> [Beneficiary]

    /// Why the library refuses `amount` shared as `beneficiaries` say, or nil when it takes it.
    func validate(amount: String, currency: Currency, beneficiaries: [Beneficiary]) -> ExpenseValidationError?

    /// Creates `expense` under a UUID derived from the user's, in place of the one it holds, its
    /// title and description taken without the whitespace around them. Returns nil once it is
    /// written, on the server first.
    func create(_ expense: Expense) async -> ExpenseSaveError?

    /// Writes `expense` over the one it edits, taken as a creation takes it.
    func update(_ expense: Expense) async -> ExpenseSaveError?

    /// Deletes the expense `id`, on the server first. Returns nil once it is.
    func delete(expenseId: String) async -> ExpenseWriteError?

    /// Marks the user's expense `id` as paid back, or no longer. Returns nil once it is.
    func setSettled(expenseId: String, isSettled: Bool) async -> ExpenseWriteError?

    /// The versions of the expense `id`, or why they could not be read: they come from the server.
    func history(expenseId: String) async -> ExpenseHistory
}
