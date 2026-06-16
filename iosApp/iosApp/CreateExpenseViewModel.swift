import ExpenseList
import Foundation
import Shared

@MainActor
class CreateExpenseViewModel: ObservableObject, CreateExpenseFormState {
    @Published var title: String = ""
    @Published var description: String = ""
    @Published var amount: String = ""
    @Published var creditor: ExpenseList.User?  // TODO: Use the logged-in user.
    @Published var currency: ExpenseList.Currency
    @Published var beneficiaries: [BeneficiaryDraft] = []
    @Published var error: ExpenseValidationError?

    @Published var users: [ExpenseList.User] = []
    @Published var searchQuery: String = "" {
        didSet {
            observeUsers()
        }
    }

    // TODO: Move this somewhere else.
    let currencies: [ExpenseList.Currency] = [
        ExpenseList.Currency(id: "EUR", name: "EUR", symbol: "€", hasSubunits: true),
        ExpenseList.Currency(id: "USD", name: "USD", symbol: "$", hasSubunits: true),
        ExpenseList.Currency(id: "JPY", name: "JPY", symbol: "¥", hasSubunits: false),
    ]

    let maxDescriptionLength: Int = Int(ExpenseLimitsKt.MAX_DESCRIPTION_LENGTH)

    static let placeholderCreditor = ExpenseList.User(
        id: "00000000-0000-0000-0000-000000000001",
        username: "currentUser",
        fullName: "Test User"
    )

    private let expenseRepository: Shared.ExpenseRepository
    private let beneficiaryRepository: Shared.BeneficiaryRepository
    private let userRepository: Shared.UserRepository
    private var userCancellable: Cancellable?

    private static let timestampFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return formatter
    }()

    init() {
        let helper = KoinHelper()
        self.expenseRepository = helper.getExpenseRepository()
        self.beneficiaryRepository = helper.getBeneficiaryRepository()
        self.userRepository = helper.getUserRepository()
        self.currency = ExpenseList.Currency(id: "EUR", name: "EUR", symbol: "€", hasSubunits: true)
        self.creditor = Self.placeholderCreditor
        observeUsers()
        recomputeError()
    }

    deinit {
        userCancellable?.cancel()
    }

    // MARK: - Mutations

    func updateTitle(_ value: String) {
        title = value
    }

    func updateDescription(_ value: String) {
        description = value
    }

    func updateAmount(_ raw: String) {
        guard let normalized = normalizeAmountInput(raw: raw, previous: amount) else {
            return
        }

        amount = normalized
        recomputeError()
    }

    func selectCurrency(_ newCurrency: ExpenseList.Currency) {
        currency = newCurrency
        amount = reformatAmount(amount, hasSubunits: newCurrency.hasSubunits)
        beneficiaries = beneficiaries.map { draft in
            var copy = draft
            copy.share = reformatAmount(draft.share, hasSubunits: newCurrency.hasSubunits)
            return copy
        }
        recomputeError()
    }

    func selectCreditor(_ user: ExpenseList.User) {
        creditor = user
    }

    func updateBeneficiaries(selectedUserUuids: Set<String>) {
        // Wholesale replace based on the picker's selection, matches Android.
        let existingByUuid = Dictionary(
            uniqueKeysWithValues: beneficiaries.map {
                ($0.userUuid, $0)
            })
        let usersByUuid = Dictionary(
            uniqueKeysWithValues: users.map {
                ($0.id, $0)
            })

        beneficiaries = selectedUserUuids.compactMap { uuid -> BeneficiaryDraft? in
            if let existing = existingByUuid[uuid] {
                return existing
            }

            guard let user = usersByUuid[uuid] else {
                return nil
            }

            return BeneficiaryDraft(
                userUuid: user.id,
                fullName: user.fullName,
                share: "0"
            )
        }
        .sorted {
            $0.fullName < $1.fullName
        }

        recomputeError()
    }

    func updateBeneficiaryShare(userUuid: String, share: String) {
        guard let index = beneficiaries.firstIndex(where: { $0.userUuid == userUuid }) else {
            return
        }

        let previous = beneficiaries[index].share
        guard let normalized = normalizeAmountInput(raw: share, previous: previous) else {
            return
        }

        beneficiaries[index].share = normalized
        recomputeError()
    }

    func clearSearchQuery() {
        searchQuery = ""
    }

    // MARK: - Persistence

    func createExpense() {
        let expenseUuid = UUID().uuidString
        let timestamp = Self.timestampFormatter.string(from: Date())
        let creditorUuid = creditor?.id ?? Self.placeholderCreditor.id

        let kotlinCurrency = Shared.Currency(
            uuid: UUID().uuidString,
            name: currency.name,
            symbol: currency.symbol,
            hasSubunits: currency.hasSubunits
        )

        let kotlinExpense = Shared.Expense(
            uuid: expenseUuid,
            timestamp: timestamp,
            title: title,
            description: description,
            amount: amount,
            creditorUuid: creditorUuid,
            isRefund: false,
            currency: kotlinCurrency,
            groupUuid: nil
        )

        expenseRepository.insert(expense: kotlinExpense) { _ in
        }

        for draft in beneficiaries {
            let kotlinBeneficiary = Shared.Beneficiary(
                uuid: UUID().uuidString,
                expenseUuid: expenseUuid,
                userUuid: draft.userUuid,
                share: draft.share
            )
            beneficiaryRepository.insert(beneficiary: kotlinBeneficiary) { _ in
            }
        }
    }

    // MARK: - User observation

    private func observeUsers() {
        userCancellable?.cancel()
        let flow = userRepository.getAllFiltered(filter: searchQuery)
        userCancellable = FlowCollectorKt.collect(
            flow,
            onEach: { [weak self] value in
                guard let self else {
                    return
                }

                guard let kotlinUsers = value as? [Shared.User] else {
                    return
                }

                self.users = kotlinUsers.map { kotlinUser in
                    ExpenseList.User(
                        id: kotlinUser.uuid,
                        username: kotlinUser.username,
                        fullName: kotlinUser.fullName
                    )
                }
            }, onCompletion: { _ in })
    }

    // MARK: - Validation

    private func recomputeError() {
        if amount.trimmingCharacters(in: .whitespaces).isEmpty {
            error = nil
            return
        }

        if beneficiaries.isEmpty
            || beneficiaries.contains(where: { $0.share.trimmingCharacters(in: .whitespaces).isEmpty })
        {
            error = .sharesEmpty
            return
        }

        let kotlinCurrency = Shared.Currency(
            uuid: "",
            name: currency.name,
            symbol: currency.symbol,
            hasSubunits: currency.hasSubunits
        )

        let kotlinBeneficiaries = beneficiaries.map { draft in
            Shared.Beneficiary(
                uuid: draft.id,
                expenseUuid: "",
                userUuid: draft.userUuid,
                share: draft.share
            )
        }

        let kotlinError = ExpenseKt.validateExpense(
            amount: amount,
            currency: kotlinCurrency,
            beneficiaries: kotlinBeneficiaries
        )

        error = mapError(kotlinError)
    }

    private func mapError(_ kotlinError: Shared.ExpenseError?) -> ExpenseValidationError? {
        guard let kotlinError else {
            return nil
        }

        switch kotlinError {
        case .sharesBelowTotalAmount: return .sharesBelowTotalAmount
        case .sharesExceedTotalAmount: return .sharesExceedTotalAmount
        case .nonPositiveShare: return .nonPositiveShare
        case .sharesEmpty: return .sharesEmpty
        case .otherKnownError: return .otherKnownError
        default: return .otherKnownError
        }
    }

    // MARK: - Amount normalization (port of utils/StringUtils.kt)

    // TODO: Put all those functions in the Shared module?

    private func normalizeAmountInput(raw: String, previous: String) -> String? {
        let formatted = formatAmountWithSubunits(raw)
        guard formatted.range(of: #"^\d*\.?\d*$"#, options: .regularExpression) != nil else {
            return nil
        }

        let hasSubunits = currency.hasSubunits
        let rounded: String
        if formatted.isEmpty {
            rounded = formatted
        } else if hasSubunits {
            rounded = roundTo(formatted, scale: 2)
        } else {
            rounded = roundTo(formatted, scale: 0)
        }

        // If the user just deleted the decimal point but the rounder re-added the
        // padded subunits, drop them so the field reflects what was typed.
        let droppedFraction =
            hasSubunits
            && previous.contains(".")
            && !rounded.contains(".")
        return droppedFraction ? String(rounded.dropLast(2)) : rounded
    }

    private func formatAmountWithSubunits(_ input: String) -> String {
        let replaced = input.replacingOccurrences(of: ",", with: ".").replacingOccurrences(of: " ", with: "")
        guard let dotIndex = replaced.firstIndex(of: ".") else {
            return replaced
        }
        let intPart = String(replaced[..<dotIndex])
        let afterDot = replaced.index(after: dotIndex)
        var fracPart = String(replaced[afterDot...].prefix(3))
        while fracPart.count < 2 {
            fracPart.append("0")
        }
        return "\(intPart).\(fracPart)"
    }

    private func roundTo(_ input: String, scale: Int) -> String {
        let trimmed = input.trimmingCharacters(in: .whitespaces)
        guard trimmed.range(of: #"^-?\d+(\.\d+)?$"#, options: .regularExpression) != nil else {
            return trimmed
        }

        if !trimmed.contains(".") {
            return trimmed
        }

        let decimal = Decimal(string: trimmed) ?? .zero
        var rounded = Decimal()
        var source = decimal

        NSDecimalRound(&rounded, &source, scale, .plain)

        let formatter = NumberFormatter()

        formatter.minimumFractionDigits = scale
        formatter.maximumFractionDigits = scale
        formatter.decimalSeparator = "."
        formatter.groupingSeparator = ""
        formatter.usesGroupingSeparator = false

        return formatter.string(from: rounded as NSDecimalNumber) ?? trimmed
    }

    private func reformatAmount(_ input: String, hasSubunits: Bool) -> String {
        if input.isEmpty {
            return input
        }

        return hasSubunits ? roundTo(input, scale: 2) : roundTo(input, scale: 0)
    }
}
