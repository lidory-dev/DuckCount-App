import ExpenseList
import Foundation
import Shared

/// The expense screens' callbacks, one-time and in groups, run by the shared module's
/// `ExpenseManagement`, as Android's `ExpenseListCallBacksImpl`, `ExpenseDetailCallBacksImpl`,
/// `CreateExpenseCallBacksImpl` and `ExpenseHistoryCallBacksImpl` run them in its app module.
@MainActor
final class ExpenseCallBacksImpl: ExpenseCallBacks {
    private let management: ExpenseManagement

    init(management: ExpenseManagement = KoinHelper().getExpenseManagement()) {
        self.management = management
    }

    var maxTitleLength: Int {
        Int(management.maxTitleLength)
    }

    var maxDescriptionLength: Int {
        Int(management.maxDescriptionLength)
    }

    var maxBeneficiaries: Int {
        Int(management.maxBeneficiaries)
    }

    var currencies: [ExpenseList.Currency] {
        management.currencies.map(\.expenseListCurrency)
    }

    var defaultCurrency: ExpenseList.Currency {
        management.defaultCurrency.expenseListCurrency
    }

    func currencyDisplayName(code: String) -> String {
        management.currencyDisplayName(code: code)
    }

    func authenticatedUserUuid() -> String? {
        management.authenticatedUserUuid()
    }

    func currentTimestamp() -> String {
        TimestampsKt.currentTimestamp()
    }

    func expenses(limit: Int) -> AsyncStream<[ExpenseList.Expense]> {
        stream(management.expenses(limit: Int32(limit))) { value in
            (value as? [ExpenseWithBeneficiaries] ?? []).map(\.expenseListExpense)
        }
    }

    func expense(id: String) -> AsyncStream<ExpenseList.Expense?> {
        stream(management.expense(uuid: id)) { value in
            (value as? ExpenseWithBeneficiaries)?.expenseListExpense
        }
    }

    func refresh() async -> Bool {
        // Kotlin only throws its cancellation over to Swift.
        (try? await management.refresh())?.boolValue ?? false
    }

    func fullNames(of userUuids: Set<String>) -> AsyncStream<[String: String]> {
        stream(management.fullNames(userUuids: Array(userUuids))) { value in
            value as? [String: String] ?? [:]
        }
    }

    func users(matching filter: String) -> AsyncStream<[ExpenseList.User]> {
        stream(management.users(filter: filter)) { value in
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
        management.computeBeneficiaries(
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
        let error = management.validate(
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
        await BackgroundTime.whileFinishing("create expense") { [management] in
            do {
                return try await management.create(expense: expense.sharedExpense)?.saveError
            } catch {
                return .write(.unknown)
            }
        }
    }

    func update(_ expense: ExpenseList.Expense) async -> ExpenseSaveError? {
        await BackgroundTime.whileFinishing("update expense") { [management] in
            do {
                return try await management.update(expense: expense.sharedExpense)?.saveError
            } catch {
                return .write(.unknown)
            }
        }
    }

    func delete(expenseId: String) async -> ExpenseWriteError? {
        await BackgroundTime.whileFinishing("delete expense") { [management] in
            do {
                return try await management.delete(uuid: expenseId)?.writeError
            } catch {
                return .unknown
            }
        }
    }

    func setSettled(expenseId: String, isSettled: Bool) async -> ExpenseWriteError? {
        await BackgroundTime.whileFinishing("mark expense") { [management] in
            do {
                return try await management.setSettled(uuid: expenseId, isSettled: isSettled)?.writeError
            } catch {
                return .unknown
            }
        }
    }

    func history(expenseId: String) async -> ExpenseHistory {
        do {
            let outcome = try await management.history(uuid: expenseId)

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

    var maxGroupExpenseEdits: Int {
        Int(management.maxGroupExpenseEdits)
    }

    func groupExpenses(groupId: String, limit: Int) -> AsyncStream<[ExpenseList.Expense]> {
        stream(management.groupExpenses(groupUuid: groupId, limit: Int32(limit))) { value in
            (value as? [ExpenseWithBeneficiaries] ?? []).map(\.expenseListExpense)
        }
    }

    func refreshGroup(groupId: String) async {
        // Kotlin only throws its cancellation over to Swift.
        try? await management.refreshGroup(groupUuid: groupId)
    }

    func groupCurrency(groupId: String) -> AsyncStream<ExpenseList.Currency?> {
        stream(management.groupCurrency(groupUuid: groupId)) { value in
            (value as? Shared.Currency)?.expenseListCurrency
        }
    }

    func isGroupLeft(groupId: String) -> AsyncStream<Bool> {
        stream(management.isGroupLeft(groupUuid: groupId)) { value in
            (value as? KotlinBoolean)?.boolValue ?? false
        }
    }

    func users(matching filter: String, inGroup groupId: String) -> AsyncStream<[ExpenseList.User]> {
        stream(management.groupUsers(filter: filter, groupUuid: groupId)) { value in
            (value as? [Shared.User] ?? []).map { ExpenseList.User(id: $0.uuid, fullName: $0.fullName) }
        }
    }

    func restore(expenseId: String) async -> ExpenseWriteError? {
        await BackgroundTime.whileFinishing("restore expense") { [management] in
            do {
                return try await management.restore(uuid: expenseId)?.writeError
            } catch {
                return .unknown
            }
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
            revision: revision?.int64Value,
            groupId: groupUuid,
            isSettlement: isSettlement,
            isDeleted: isDeleted,
            deletedByUuid: deletedByUuid
        )
    }
}

extension ExpenseList.Expense {
    /// What the shared module writes: the form's, which never makes a settlement, its deletion and a
    /// one-time expense's mark being written apart, and which the writers ignore.
    fileprivate var sharedExpense: ExpenseWithBeneficiaries {
        ExpenseWithBeneficiaries(
            uuid: id,
            title: title,
            timestamp: timestamp,
            description: description,
            amount: amount,
            creditorUuid: creditorUuid,
            isSettlement: isSettlement,
            currency: currency.sharedCurrency,
            groupUuid: groupId,
            isDeleted: isDeleted,
            beneficiaries: beneficiaries.map { $0.sharedBeneficiary(expenseUuid: id) },
            isSettled: false,
            settledAt: nil,
            revision: revision.map { KotlinLong(value: $0) },
            deletedByUuid: deletedByUuid
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

        if self is ExpenseSaveFailureGroupLeft {
            return .groupLeft
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
        case .changedMeanwhile: .changedMeanwhile
        case .groupLeft: .groupLeft
        case .creationPending: .creationPending
        case .awaitingOwner: .awaitingOwner
        case .rateLimited: .rateLimited
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
