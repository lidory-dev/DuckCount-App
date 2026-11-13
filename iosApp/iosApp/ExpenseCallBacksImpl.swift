import ExpenseList
import Foundation
import Shared

/// The expense screens' callbacks, run by the shared module's `OneTimeExpenses`, as Android's
/// `ExpenseListCallBacksImpl`, `ExpenseDetailCallBacksImpl`, `CreateExpenseCallBacksImpl` and
/// `ExpenseHistoryCallBacksImpl` run them in its app module.
@MainActor
final class ExpenseCallBacksImpl: ExpenseCallBacks {
    private let oneTimeExpenses: OneTimeExpenses

    init(oneTimeExpenses: OneTimeExpenses = KoinHelper().getOneTimeExpenses()) {
        self.oneTimeExpenses = oneTimeExpenses
    }

    var maxTitleLength: Int {
        Int(oneTimeExpenses.maxTitleLength)
    }

    var maxDescriptionLength: Int {
        Int(oneTimeExpenses.maxDescriptionLength)
    }

    var maxBeneficiaries: Int {
        Int(oneTimeExpenses.maxBeneficiaries)
    }

    var currencies: [ExpenseList.Currency] {
        oneTimeExpenses.currencies.map(\.expenseListCurrency)
    }

    var defaultCurrency: ExpenseList.Currency {
        oneTimeExpenses.defaultCurrency.expenseListCurrency
    }

    func currencyDisplayName(code: String) -> String {
        oneTimeExpenses.currencyDisplayName(code: code)
    }

    func authenticatedUserUuid() -> String? {
        oneTimeExpenses.authenticatedUserUuid()
    }

    func expenses() -> AsyncStream<[ExpenseList.Expense]> {
        stream(oneTimeExpenses.expenses()) { value in
            (value as? [ExpenseWithBeneficiaries] ?? []).map(\.expenseListExpense)
        }
    }

    func expense(id: String) -> AsyncStream<ExpenseList.Expense?> {
        stream(oneTimeExpenses.expense(uuid: id)) { value in
            (value as? ExpenseWithBeneficiaries)?.expenseListExpense
        }
    }

    func refresh() async -> Bool {
        // Kotlin only throws its cancellation over to Swift.
        (try? await oneTimeExpenses.refresh())?.boolValue ?? false
    }

    func fullNames(of userUuids: Set<String>) -> AsyncStream<[String: String]> {
        stream(oneTimeExpenses.fullNames(userUuids: Array(userUuids))) { value in
            value as? [String: String] ?? [:]
        }
    }

    func users(matching filter: String) -> AsyncStream<[ExpenseList.User]> {
        stream(oneTimeExpenses.users(filter: filter)) { value in
            (value as? [Shared.User] ?? []).map { ExpenseList.User(id: $0.uuid, fullName: $0.fullName) }
        }
    }

    func computeBeneficiaries(
        amount: String,
        currency: ExpenseList.Currency,
        beneficiaryUserUuids: [String],
        fixedShares: [String: String],
        sharePercentages: [String: String],
        shareWeights: [String: String]
    ) -> [ExpenseList.Beneficiary] {
        oneTimeExpenses.computeBeneficiaries(
            amount: amount,
            currency: currency.sharedCurrency,
            beneficiaryUserUuids: beneficiaryUserUuids,
            fixedShares: fixedShares,
            sharePercentages: sharePercentages,
            shareWeights: shareWeights
        )
        .map(\.expenseListBeneficiary)
    }

    func validate(
        amount: String,
        currency: ExpenseList.Currency,
        beneficiaries: [ExpenseList.Beneficiary]
    ) -> ExpenseValidationError? {
        let error = oneTimeExpenses.validate(
            amount: amount,
            currency: currency.sharedCurrency,
            beneficiaries: beneficiaries.map { $0.sharedBeneficiary(expenseUuid: "") }
        )

        guard let error else {
            return nil
        }

        // Kotlin enums cross over as classes, so the last entry stands in for the default Swift
        // asks for.
        switch error {
        case .sharesBelowTotalAmount: return .sharesBelowTotalAmount
        case .sharesExceedTotalAmount: return .sharesExceedTotalAmount
        case .nonPositiveShare, .sharesEmpty: return .nonPositiveShare
        case .noBeneficiaries: return .noBeneficiaries
        case .tooManyBeneficiaries: return .tooManyBeneficiaries
        default: return .invalidAmount
        }
    }

    func create(_ expense: ExpenseList.Expense) async -> ExpenseSaveError? {
        do {
            return try await oneTimeExpenses.create(expense: expense.sharedExpense)?.saveError
        } catch {
            return .write(.unknown)
        }
    }

    func update(_ expense: ExpenseList.Expense) async -> ExpenseSaveError? {
        do {
            return try await oneTimeExpenses.update(expense: expense.sharedExpense)?.saveError
        } catch {
            return .write(.unknown)
        }
    }

    func delete(expenseId: String) async -> ExpenseWriteError? {
        do {
            return try await oneTimeExpenses.delete(uuid: expenseId)?.writeError
        } catch {
            return .unknown
        }
    }

    func setSettled(expenseId: String, isSettled: Bool) async -> ExpenseWriteError? {
        do {
            return try await oneTimeExpenses.setSettled(uuid: expenseId, isSettled: isSettled)?.writeError
        } catch {
            return .unknown
        }
    }

    func history(expenseId: String) async -> ExpenseHistory {
        do {
            let outcome = try await oneTimeExpenses.history(uuid: expenseId)

            if let read = outcome as? ExpenseHistoryOutcomeVersions {
                return .versions(
                    read.versions.map { version in
                        ExpenseVersion(
                            revision: version.revision,
                            expense: version.expense.expenseListExpense,
                            authorUuid: version.authorUuid,
                            isCreation: version.isCreation,
                            madeAt: version.madeAt
                        )
                    }
                )
            }

            return .unavailable((outcome as? ExpenseHistoryOutcomeUnavailable)?.failure.historyError ?? .unknown)
        } catch {
            return .unavailable(.unknown)
        }
    }
}

extension Shared.Currency {
    fileprivate var expenseListCurrency: ExpenseList.Currency {
        // Its name is its ISO 4217 code, which every device names it by.
        ExpenseList.Currency(id: uuid, code: name, symbol: symbol, hasSubunits: hasSubunits)
    }
}

extension ExpenseList.Currency {
    fileprivate var sharedCurrency: Shared.Currency {
        Shared.Currency(uuid: id, name: code, symbol: symbol, hasSubunits: hasSubunits)
    }
}

extension Shared.Beneficiary {
    fileprivate var expenseListBeneficiary: ExpenseList.Beneficiary {
        ExpenseList.Beneficiary(
            id: uuid,
            userUuid: userUuid,
            share: share,
            isShareFixed: isShareFixed,
            sharePercentage: sharePercentage,
            shareWeight: shareWeight
        )
    }
}

extension ExpenseList.Beneficiary {
    fileprivate func sharedBeneficiary(expenseUuid: String) -> Shared.Beneficiary {
        Shared.Beneficiary(
            uuid: id,
            expenseUuid: expenseUuid,
            userUuid: userUuid,
            share: share,
            isShareFixed: isShareFixed,
            sharePercentage: sharePercentage,
            shareWeight: shareWeight
        )
    }
}

extension ExpenseWithBeneficiaries {
    fileprivate var expenseListExpense: ExpenseList.Expense {
        ExpenseList.Expense(
            id: uuid,
            timestamp: timestamp,
            title: title,
            description: description_,
            amount: amount,
            creditorUuid: creditorUuid,
            currency: currency.expenseListCurrency,
            beneficiaries: beneficiaries.map(\.expenseListBeneficiary),
            isSettled: isSettled,
            settledAt: settledAt?.description(),
            revision: revision?.int64Value
        )
    }
}

extension ExpenseList.Expense {
    /// What the shared module writes: a one-time expense, which is never a settlement, nor shown
    /// deleted, and whose mark is written apart.
    fileprivate var sharedExpense: ExpenseWithBeneficiaries {
        ExpenseWithBeneficiaries(
            uuid: id,
            title: title,
            timestamp: timestamp,
            description: description,
            amount: amount,
            creditorUuid: creditorUuid,
            isSettlement: false,
            currency: currency.sharedCurrency,
            groupUuid: nil,
            isDeleted: false,
            beneficiaries: beneficiaries.map { $0.sharedBeneficiary(expenseUuid: id) },
            isSettled: false,
            settledAt: nil,
            revision: revision.map { KotlinLong(value: $0) },
            deletedByUuid: nil
        )
    }
}

extension ExpenseSaveFailure {
    fileprivate var saveError: ExpenseSaveError {
        if let deleted = self as? ExpenseSaveFailureBeneficiariesDeleted {
            return .beneficiariesDeleted(
                deleted.beneficiaries.map { ExpenseList.DeletedBeneficiary(userUuid: $0.userUuid, fullName: $0.fullName) }
            )
        }

        return .write((self as? ExpenseSaveFailureWrite)?.failure.writeError ?? .unknown)
    }
}

extension ExpenseWriteFailure {
    /// This failure as the screens tell it.
    fileprivate var writeError: ExpenseWriteError {
        // Kotlin enums cross over as classes, so the last entry stands in for the default Swift
        // asks for.
        switch self {
        case .noInternet: .noInternet
        case .timeout: .timeout
        case .serverUnreachable: .serverUnreachable
        case .invalidData: .invalidData
        case .conflict: .conflict
        case .unauthorized: .unauthorized
        case .keyMissing: .keyMissing
        default: .unknown
        }
    }
}

extension ExpenseHistoryFailure {
    fileprivate var historyError: ExpenseHistoryError {
        switch self {
        case .noInternet: .noInternet
        case .timeout: .timeout
        case .serverUnreachable: .serverUnreachable
        default: .unknown
        }
    }
}
