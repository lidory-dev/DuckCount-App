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

    /// Now, as a new expense is dated, written exactly as every device expects to read it back:
    /// an ISO 8601 date and time with no time zone and no trailing zeros in its fraction of a
    /// second, which an expense dated any other way is refused for.
    func currentTimestamp() -> String

    /// The newest `limit` one-time expenses, newest first, as they change: a list asks for more as it
    /// scrolls, see `ExpensePager`.
    func expenses(limit: Int) -> AsyncStream<[Expense]>

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
    /// A group expense's are each by whichever member made them.
    func history(expenseId: String) async -> ExpenseHistory

    // A group's expenses. `create`, `update`, `delete` and `history` take them too, routed by
    // `Expense.groupId`, or by the stored expense's group.

    /// The most edits a group expense takes after its creation: it can be edited no more once its
    /// revision reaches 1 + this.
    var maxGroupExpenseEdits: Int { get }

    /// The newest `limit` expenses of the group `groupId`, deleted ones included, newest first, as
    /// they change, see `expenses(limit:)`.
    func groupExpenses(groupId: String, limit: Int) -> AsyncStream<[Expense]>

    /// Brings the group `groupId` and its expenses up to date with the server.
    func refreshGroup(groupId: String) async

    /// The currency of the group `groupId`, which every expense of it is in, or nil once there is no
    /// such group on the device.
    func groupCurrency(groupId: String) -> AsyncStream<Currency?>

    /// Whether the user is no longer a member of the group `groupId`, which leaves its expenses
    /// read-only. It can change with a screen open.
    func isGroupLeft(groupId: String) -> AsyncStream<Bool>

    /// The members of the group `groupId` whose name contains `filter`, whom an expense of it can be
    /// paid by and name: only those the device holds a name for.
    func users(matching filter: String, inGroup groupId: String) -> AsyncStream<[User]>

    /// Restores the deleted group expense `expenseId`, which is no edit. Returns nil once the
    /// server took it.
    func restore(expenseId: String) async -> ExpenseWriteError?
}
