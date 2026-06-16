import Combine

public protocol CreateExpenseFormState: ObservableObject {
    var title: String { get set }
    var description: String { get set }
    var amount: String { get }
    var creditor: User? { get }  // TODO: Use the logged-in user.
    var currency: Currency { get }
    var currencies: [Currency] { get }
    var beneficiaries: [BeneficiaryDraft] { get }
    var error: ExpenseValidationError? { get }
    var users: [User] { get }
    var searchQuery: String { get set }
    var maxDescriptionLength: Int { get }
    var maxTitleLength: Int { get }

    func updateAmount(_ raw: String)
    func selectCurrency(_ currency: Currency)
    func selectCreditor(_ user: User)
    func updateBeneficiaries(selectedUserUuids: Set<String>)
    func updateBeneficiaryShare(userUuid: String, share: String)
    func clearSearchQuery()
}
