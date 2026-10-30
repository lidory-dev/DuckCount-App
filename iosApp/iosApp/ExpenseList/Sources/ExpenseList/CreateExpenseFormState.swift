import Combine
import Foundation

@MainActor
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

public extension CreateExpenseFormState {
    /// `title` without the whitespace around it, as the expense is saved with it, which the form
    /// is checked by. The field holds it as typed: trimmed as it is typed, a space typed after a
    /// word would be swallowed, and the cursor moved back over it.
    var trimmedTitle: String { title.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// `description` without the whitespace around it, as `trimmedTitle` is `title`.
    var trimmedDescription: String { description.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// How long `trimmedTitle` is against `maxTitleLength`: in UTF-16 units, the unit every text
    /// limit of the app is counted in, as Android counts it and a receiving device checks it. Not
    /// in `Character`s, of which an emoji is one but two units, and a flag one but four, so that a
    /// title within the limit here would be past it everywhere else.
    var titleLength: Int { trimmedTitle.utf16.count }

    /// How long `trimmedDescription` is against `maxDescriptionLength`, counted as `titleLength`.
    var descriptionLength: Int { trimmedDescription.utf16.count }
}
