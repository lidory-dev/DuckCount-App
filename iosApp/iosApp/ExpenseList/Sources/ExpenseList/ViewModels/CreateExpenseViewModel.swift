import Foundation

/// The expense form as it stands, as Android's `CreateExpenseState` holds it.
struct ExpenseForm: Equatable {
    /// When the expense being edited happened, which an edit keeps; empty when creating.
    var timestamp = ""

    /// The title field's text, as typed: the expense is checked and saved without the whitespace
    /// around it, see `trimmedTitle`. Trimmed as it is typed, a space typed after a word would be
    /// swallowed.
    var title = ""

    /// The description field's text, as typed, as `title` is.
    var description = ""

    /// The amount, as the form uses and saves it, see `toAmount`.
    var amount = ""

    /// The amount field's text, as typed until the field is left.
    var amountText = ""

    var creditorUuid = ""
    var currency: Currency

    /// In the order they were picked, which decides who the library gives the cents that do not
    /// divide evenly to: the rows are ordered apart, see `CreateExpenseViewModel.displayedBeneficiaries`.
    var beneficiaries: [Beneficiary] = []

    /// The text of each share field being typed in, by the beneficiary's user UUID: the share itself
    /// is in `beneficiaries`.
    var shareTexts: [String: String] = [:]

    /// The revision of the version the form was read from: the one an edit of a group expense is
    /// written after.
    var revision: Int64?

    /// Whether the group expense being edited is deleted, as the device showed it once the form was
    /// read, or as it was about to be saved, see `ExpenseSaveError.deletedMeanwhile`: the form then
    /// saves nothing. Deleting is no edit, so the server would take an edit written after it, which
    /// would then count in no balance.
    var isDeleted = false

    var trimmedTitle: String {
        title.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var trimmedDescription: String {
        description.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// What saving the form writes of the expense, and so what an edit has to change. The title and
    /// description are taken as they are saved, and each share by whose it is: working the shares
    /// out again gives every beneficiary a new identifier, which changes nothing.
    fileprivate var savedContent: SavedContent {
        SavedContent(
            title: trimmedTitle,
            description: trimmedDescription,
            amount: amount,
            currencyCode: currency.code,
            currencySymbol: currency.symbol,
            hasSubunits: currency.hasSubunits,
            creditorUuid: creditorUuid,
            shares: Set(
                beneficiaries.map {
                    SavedShare(
                        userUuid: $0.userUuid,
                        share: $0.share,
                        isShareFixed: $0.isShareFixed,
                        sharePercentage: $0.sharePercentage,
                        shareWeight: $0.shareWeight
                    )
                }
            )
        )
    }
}

private struct SavedContent: Equatable {
    let title: String
    let description: String
    let amount: String
    let currencyCode: String
    let currencySymbol: String
    let hasSubunits: Bool
    let creditorUuid: String
    let shares: Set<SavedShare>
}

private struct SavedShare: Hashable {
    let userUuid: String
    let share: String
    let isShareFixed: Bool
    let sharePercentage: String?
    let shareWeight: String?
}

/// The expense form's state, a new expense's or one being edited, as Android's
/// `CreateExpenseViewModel` holds it: a one-time expense's, whose creditor is always the user, or one
/// of a group's, in the group's currency, paid by any of its members and shared among them.
@MainActor
public final class CreateExpenseViewModel: ObservableObject {
    @Published private(set) var form: ExpenseForm {
        didSet {
            error = validationError()
        }
    }

    /// Why the amount and shares cannot be saved as they stand.
    @Published private(set) var error: ExpenseValidationError?

    /// Whether the expense being edited has yet to be read into the form: its title and amount are
    /// then not missing, only not here yet.
    @Published private(set) var isBeingRead: Bool

    /// True while the expense is being written, which the form waits on: leaving would cancel it.
    @Published private(set) var isSaving = false

    /// True once it is written, which closes the form.
    @Published private(set) var isSaved = false

    /// Why the last save failed, until it is told.
    @Published var saveError: ExpenseSaveError?

    /// The names of the creditor and the beneficiaries, as they are read.
    @Published private(set) var names = NameBook()

    /// Who the payer and beneficiary pickers list: the user's contacts and themself, or the members
    /// of the group.
    @Published private(set) var users: [User] = []

    /// The picker's search field's text.
    @Published private(set) var searchQuery = ""

    /// Who the beneficiary picker has selected, in the order they were, see `ExpenseForm.beneficiaries`.
    @Published private(set) var selectedUuids: [String] = []

    let maxTitleLength: Int
    let maxDescriptionLength: Int
    let maxBeneficiaries: Int
    let currencies: [Currency]
    let selfUuid: String?

    /// The expense being edited, nil when creating one.
    let expenseId: String?

    /// The group the expense is in, nil for a one-time expense.
    let groupId: String?

    /// The form as it stood once the expense being edited was read into it: what an edit has to
    /// differ from for there to be anything to save.
    private var loadedForm: ExpenseForm?

    private let callBacks: any ExpenseCallBacks
    private let usersTask = TaskHandle()
    private var nameFollower: NameFollower?

    public init(callBacks: any ExpenseCallBacks, groupId: String? = nil, expenseId: String? = nil) {
        self.callBacks = callBacks
        self.groupId = groupId
        self.expenseId = expenseId
        self.maxTitleLength = callBacks.maxTitleLength
        self.maxDescriptionLength = callBacks.maxDescriptionLength
        self.maxBeneficiaries = callBacks.maxBeneficiaries
        self.currencies = callBacks.currencies

        let selfUuid = callBacks.authenticatedUserUuid()

        self.selfUuid = selfUuid
        // A group expense waits for its group's currency too, which decides how its amount is typed.
        self.isBeingRead = expenseId != nil || groupId != nil
        // A one-time expense is always the user's to have paid: the server takes no other creditor.
        // A group expense is too until another member is picked.
        self.form = ExpenseForm(creditorUuid: selfUuid ?? "", currency: callBacks.defaultCurrency)

        error = validationError()

        nameFollower = NameFollower { [weak self] names in
            self?.names = names
        }

        followNames()
        searchUsers()

        if isBeingRead {
            Task {
                await readForm()
            }
        }
    }

    var isNewExpense: Bool {
        expenseId == nil
    }

    /// Whether the expense is a group's.
    var isGroupExpense: Bool {
        groupId != nil
    }

    /// Whether the expense being edited has changed since it was read: saved unchanged, it would
    /// spend one of the few edits the server allows, and add a version that says nothing new.
    var hasChanges: Bool {
        guard let loadedForm else {
            return false
        }

        return form.savedContent != loadedForm.savedContent
    }

    /// Whether leaving the form would lose anything typed into it.
    var hasEdits: Bool {
        if !isNewExpense {
            return hasChanges
        }

        return !form.trimmedTitle.isEmpty || !form.amount.isBlank || !form.trimmedDescription.isEmpty
            || !form.beneficiaries.isEmpty
    }

    /// How long the title is against `maxTitleLength`: in UTF-16 units, the unit every text limit of
    /// the app is counted in, as Android counts it and a receiving device checks it.
    var titleLength: Int {
        form.trimmedTitle.utf16.count
    }

    /// How long the description is against `maxDescriptionLength`, counted as the title is.
    var descriptionLength: Int {
        form.trimmedDescription.utf16.count
    }

    /// Whether the form can be saved: a title and a description the limits take, an amount the
    /// library takes shared as the form says, and for an expense being edited, a change, and no
    /// deletion of it, see `ExpenseForm.isDeleted`.
    var canSave: Bool {
        !isSaving && !isSaved && !isBeingRead && !form.isDeleted
            && (isNewExpense || hasChanges)
            && error == nil
            && !form.amount.isBlank
            && (1...maxTitleLength).contains(titleLength)
            && descriptionLength <= maxDescriptionLength
    }

    /// The beneficiaries as the form lists them: the user first and the others by name. Ordered here
    /// alone, for the rows: the expense keeps them in the order they were picked.
    var displayedBeneficiaries: [Beneficiary] {
        form.beneficiaries.sortedByName(
            selfUuid: selfUuid,
            uuidOf: \.userUuid,
            nameOf: { self.names.name(of: $0.userUuid) }
        )
    }

    /// The text the share field of `beneficiary` shows: as typed while it is being typed in.
    func shareText(of beneficiary: Beneficiary) -> String {
        form.shareTexts[beneficiary.userUuid] ?? beneficiary.share
    }

    func currencyDisplayName(_ currency: Currency) -> String {
        callBacks.currencyDisplayName(code: currency.code)
    }

    // MARK: - Fields

    func updateTitle(_ title: String) {
        form.title = title
    }

    func updateDescription(_ description: String) {
        form.description = description
    }

    /// Takes `text`, the amount field's text, as typed, neither formatted nor rounded, unless it is
    /// no amount the field takes, see `isAmountInput`, and works the shares out again from it. The
    /// amount it stands for is what the form uses and saves, and the field shows it written so once
    /// the user is done with it, see `finishAmount`. Says whether the field took it.
    @discardableResult
    func typeAmount(_ text: String) -> Bool {
        let typed = text.droppingDecimalsIfSeparatorDeleted(previous: form.amountText)

        guard typed.isAmountInput(hasSubunits: form.currency.hasSubunits) else {
            return false
        }

        form.amountText = typed
        form.amount = typed.toAmount(hasSubunits: form.currency.hasSubunits)
        calculateShares()

        return true
    }

    /// Writes the amount field's text as the amount it stands for, once the field is left.
    func finishAmount() {
        form.amountText = form.amount
    }

    /// The user picked `currency`: the form is written anew for it, and the shares worked out again
    /// from the amount as it now stands, which those rounded one by one need not add up to.
    func selectCurrency(_ currency: Currency) {
        updateCurrency(currency)
        calculateShares()
    }

    /// Takes `text`, the share field of `userUuid` as typed, as `typeAmount` takes the amount's,
    /// fixes the share there and works the others out again from what it leaves. Says whether the
    /// field took it.
    @discardableResult
    func typeShare(userUuid: String, text: String) -> Bool {
        guard let share = form.beneficiaries.first(where: { $0.userUuid == userUuid })?.share else {
            return false
        }

        let typed = text.droppingDecimalsIfSeparatorDeleted(previous: form.shareTexts[userUuid] ?? share)
        let isTaken = typed.isAmountInput(hasSubunits: form.currency.hasSubunits)

        if isTaken {
            form.shareTexts[userUuid] = typed
            updateShare(userUuid: userUuid, share: typed)
        }

        setFixedShare(userUuid: userUuid)
        calculateShares()

        return isTaken
    }

    /// Shows the share of `userUuid` as the form keeps it, once its field is left.
    func finishShare(userUuid: String) {
        form.shareTexts[userUuid] = nil
    }

    /// Lets the share of `userUuid` be worked out again, rather than as it was fixed.
    func removeFixedShare(userUuid: String) {
        // Worked out again from here on, so what was typed there no longer stands.
        finishShare(userUuid: userUuid)

        updateBeneficiaries(
            form.beneficiaries.map { beneficiary in
                guard beneficiary.userUuid == userUuid else {
                    return beneficiary
                }

                var unfixed = beneficiary
                unfixed.isShareFixed = false
                unfixed.sharePercentage = nil
                unfixed.shareWeight = nil

                return unfixed
            }
        )

        calculateShares()
    }

    // MARK: - Payer picker

    /// Opens the payer picker, with nothing searched.
    func beginPickingCreditor() {
        search("")
    }

    /// Makes `userUuid` the group expense's payer, whose name follows. A one-time expense is always
    /// the user's to have paid, so its payer is never picked.
    func selectCreditor(_ userUuid: String) {
        guard isGroupExpense else {
            return
        }

        form.creditorUuid = userUuid
        followNames()
    }

    // MARK: - Beneficiary picker

    /// Opens the picker on the beneficiaries as they are, with nothing searched.
    func beginPickingBeneficiaries() {
        selectedUuids = form.beneficiaries.map(\.userUuid)
        search("")
    }

    /// `userUuid` taken out of the selection, or added to it when that keeps it within the limit:
    /// past it, selecting one more does nothing, and the picker shows them disabled.
    func toggleSelection(userUuid: String) {
        if let index = selectedUuids.firstIndex(of: userUuid) {
            selectedUuids.remove(at: index)
        } else if selectedUuids.count < maxBeneficiaries {
            selectedUuids.append(userUuid)
        }
    }

    /// How much of the listed people the beneficiary picker has selected, which its "Select All"
    /// shows: all of them, some of the selection, or nobody.
    var selectAllState: SelectAllState {
        if !users.isEmpty, users.allSatisfy({ selectedUuids.contains($0.id) }) {
            return .all
        }

        return selectedUuids.isEmpty ? .none : .some
    }

    /// Whether the beneficiary picker offers "Select All": for a group alone, whose members are to
    /// number no more than an expense names, and over all of them, so not while searching. A search
    /// of nothing but whitespace lists them all, the search being for the text without it.
    var isSelectAllShown: Bool {
        isGroupExpense && searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !users.isEmpty
    }

    /// Selects every listed member of the group, or nobody once they all are. Those selected are
    /// added after the selection as it stands, in the order the picker lists them, which keeps the
    /// order the others were picked in. Not counted against `maxBeneficiaries`: a group holds no
    /// more members than an expense names.
    func toggleSelectAll() {
        guard isGroupExpense else {
            return
        }

        if selectAllState == .all {
            selectedUuids = []
        } else {
            let selected = Set(selectedUuids)

            selectedUuids += users.map(\.id).filter { !selected.contains($0) }
        }
    }

    func search(_ query: String) {
        guard query != searchQuery else {
            return
        }

        searchQuery = query
        searchUsers()
    }

    /// Makes the picker's selection the expense's beneficiaries.
    func confirmSelection() {
        selectBeneficiaries(selectedUuids)
    }

    /// Makes `userUuids` the beneficiaries, in that order, and works their shares out, those fixed
    /// before kept for whoever stays.
    func selectBeneficiaries(_ userUuids: [String]) {
        let fixed = form.beneficiaries.filter(\.isShareFixed)
        let fixedUuids = Set(fixed.map(\.userUuid))
        let kept = fixed.filter { userUuids.contains($0.userUuid) }

        let beneficiaries = computeBeneficiaries(
            amount: form.amount.isBlank ? "0" : form.amount,
            userUuids: userUuids,
            fixedShares: kept.fixedShares,
            sharePercentages: kept.sharePercentages,
            shareWeights: kept.shareWeightsSetByHand
        )

        updateBeneficiaries(
            beneficiaries.map { beneficiary in
                var marked = beneficiary

                if fixedUuids.contains(beneficiary.userUuid) {
                    marked.isShareFixed = true
                }

                return marked
            }
        )
    }

    // MARK: - Saving

    func save() {
        // Not once saved either: a creation would add the expense twice, and an edit spend another
        // of the few the server allows on a version that says nothing new.
        guard !isSaving, !isSaved else {
            return
        }

        isSaving = true

        let saved = form

        Task {
            let failure: ExpenseSaveError?

            if let expenseId {
                // Deleting a group expense is no edit of it, so the server takes an edit written
                // after a deletion: the form saves nothing over one the device shows by now. Not for
                // a one-time expense, never shown deleted, and gone once it is.
                if isGroupExpense, await firstShown(expenseId: expenseId)?.isDeleted == true {
                    form.isDeleted = true
                    isSaving = false
                    saveError = .deletedMeanwhile
                    return
                }

                // The date it was created with: an edit changes what the expense says, not when it
                // happened, and its history tells when each version was made.
                failure = await callBacks.update(expense(from: saved, id: expenseId, timestamp: saved.timestamp))
            } else {
                // Until it is written: it is then given a UUID derived from the user's.
                failure = await callBacks.create(
                    expense(from: saved, id: UUID().uuidString.lowercased(), timestamp: callBacks.currentTimestamp())
                )
            }

            if let failure {
                let told = await handled(failure)

                isSaving = false
                saveError = told
            } else {
                isSaving = false
                isSaved = true
            }
        }
    }

    /// Does what the failure calls for on the form, and returns what to tell of it.
    ///
    /// When it was for naming people who deleted their account, they are taken out of the selection
    /// and the shares worked out again without them, for the user to save again: the people they are
    /// picked from no longer list them.
    ///
    /// When another member changed the group expense first, the form is read again from the version
    /// the device shows now, over what the user typed: they see what they would write over, and save
    /// from it, every save built on the version read before being refused the same way. Unless it
    /// now shows the expense deleted, which the form saves nothing over.
    private func handled(_ failure: ExpenseSaveError) async -> ExpenseSaveError {
        switch failure {
        case .beneficiariesDeleted(let deleted):
            let deletedUuids = Set(deleted.map(\.userUuid))

            selectBeneficiaries(form.beneficiaries.map(\.userUuid).filter { !deletedUuids.contains($0) })

            return failure

        case .write(.changedMeanwhile):
            if let expenseId {
                await read(expenseId: expenseId)
            }

            return form.isDeleted ? .deletedMeanwhile : failure

        default:
            return failure
        }
    }

    private func expense(from form: ExpenseForm, id: String, timestamp: String) -> Expense {
        Expense(
            id: id,
            timestamp: timestamp,
            title: form.title,
            description: form.description,
            amount: form.amount,
            creditorUuid: form.creditorUuid,
            currency: form.currency,
            beneficiaries: form.beneficiaries,
            revision: form.revision,
            groupId: groupId
        )
    }

    // MARK: - Reading

    /// Reads what the form starts from: the group's currency, which every expense of it is in, then
    /// the expense being edited.
    private func readForm() async {
        if let groupId {
            await readCurrency(groupId: groupId)
        }

        if let expenseId {
            await read(expenseId: expenseId)
        } else {
            isBeingRead = false
        }
    }

    /// Writes the form in the currency of the group `groupId`, unless there is no such group on the
    /// device any more, which the save then says.
    private func readCurrency(groupId: String) async {
        for await currency in callBacks.groupCurrency(groupId: groupId) {
            if let currency {
                updateCurrency(currency)
            }

            break
        }
    }

    /// The expense `expenseId` as the device shows it now, or nil when it shows none.
    private func firstShown(expenseId: String) async -> Expense? {
        for await expense in callBacks.expense(id: expenseId) {
            return expense
        }

        return nil
    }

    /// Reads the expense `expenseId` into the form, as the device shows it, and takes the form as it
    /// then stands for what an edit has to differ from.
    private func read(expenseId: String) async {
        var read: Expense?

        for await expense in callBacks.expense(id: expenseId) {
            if let expense {
                read = expense
                break
            }
        }

        guard let expense = read else {
            return
        }

        // The currency first: it decides how the amount and the shares are written.
        updateCurrency(expense.currency)
        form.title = expense.title
        form.creditorUuid = expense.creditorUuid
        // Typed anew, rather than as an edit of what the field held, read before.
        form.amountText = ""
        typeAmount(expense.amount)
        form.description = expense.description
        form.timestamp = expense.timestamp
        form.revision = expense.revision
        form.isDeleted = expense.isDeleted
        updateBeneficiaries(expense.beneficiaries)

        for beneficiary in expense.beneficiaries {
            updateShare(userUuid: beneficiary.userUuid, share: beneficiary.share)
        }

        isBeingRead = false
        loadedForm = form
    }

    // MARK: - Shares

    private func updateCurrency(_ currency: Currency) {
        let scale = currency.hasSubunits ? 2 : 0

        func reformat(_ value: String) -> String {
            value.isBlank ? value : value.rounded(scale: scale)
        }

        let amount = reformat(form.amount)

        form.currency = currency
        form.amount = amount
        form.amountText = amount
        form.beneficiaries = form.beneficiaries.map { beneficiary in
            var reformatted = beneficiary
            reformatted.share = reformat(beneficiary.share)

            return reformatted
        }
        form.shareTexts = [:]
    }

    private func updateBeneficiaries(_ beneficiaries: [Beneficiary]) {
        let userUuids = Set(beneficiaries.map(\.userUuid))

        form.beneficiaries = beneficiaries
        form.shareTexts = form.shareTexts.filter { userUuids.contains($0.key) }
        selectedUuids = beneficiaries.map(\.userUuid)

        followNames()
    }

    /// Sets the share of `userUuid` to `share`, as worked out or read back, written as the form
    /// keeps it.
    private func updateShare(userUuid: String, share: String) {
        guard let index = form.beneficiaries.firstIndex(where: { $0.userUuid == userUuid }) else {
            return
        }

        form.beneficiaries[index].share = share.toAmount(hasSubunits: form.currency.hasSubunits)
    }

    /// Makes the share of `userUuid` the amount it is now: if it was a percentage or a weight, it is
    /// one no more.
    private func setFixedShare(userUuid: String) {
        guard let index = form.beneficiaries.firstIndex(where: { $0.userUuid == userUuid }) else {
            return
        }

        form.beneficiaries[index].isShareFixed = true
        form.beneficiaries[index].sharePercentage = nil
        form.beneficiaries[index].shareWeight = nil
    }

    private func calculateShares() {
        let beneficiaries = computeBeneficiaries(
            amount: form.amount.validNumber,
            userUuids: form.beneficiaries.map(\.userUuid),
            fixedShares: form.beneficiaries.fixedShares.mapValues(\.validNumber),
            sharePercentages: form.beneficiaries.sharePercentages,
            shareWeights: form.beneficiaries.shareWeightsSetByHand
        )

        for beneficiary in beneficiaries {
            updateShare(userUuid: beneficiary.userUuid, share: beneficiary.share)
        }

        // The library states a weight for everybody sharing what is left once anybody has one, and
        // for nobody once nobody does.
        let weights = Dictionary(beneficiaries.map { ($0.userUuid, $0.shareWeight) }, uniquingKeysWith: { first, _ in first })

        for index in form.beneficiaries.indices {
            form.beneficiaries[index].shareWeight = weights[form.beneficiaries[index].userUuid] ?? nil
        }
    }

    private func computeBeneficiaries(
        amount: String,
        userUuids: [String],
        fixedShares: [String: String],
        sharePercentages: [String: String],
        shareWeights: [String: String]
    ) -> [Beneficiary] {
        callBacks.computeBeneficiaries(
            amount: amount.validNumber,
            currency: form.currency,
            beneficiaryUserUuids: userUuids,
            fixedShares: fixedShares,
            sharePercentages: sharePercentages,
            shareWeights: shareWeights
        )
        .map { beneficiary in
            var marked = beneficiary
            let uuid = beneficiary.userUuid

            if fixedShares[uuid] != nil || sharePercentages[uuid] != nil || shareWeights[uuid] != nil {
                marked.isShareFixed = true
            }

            return marked
        }
    }

    private func validationError() -> ExpenseValidationError? {
        // Nothing is saved without an amount, which the field says itself.
        if form.amount.isBlank {
            return nil
        }

        if form.beneficiaries.count == 1, form.beneficiaries[0].userUuid == form.creditorUuid {
            return .noBeneficiaryOtherThanCreditor
        }

        if form.beneficiaries.contains(where: { $0.share.isBlank }) {
            return .nonPositiveShare
        }

        return callBacks.validate(amount: form.amount, currency: form.currency, beneficiaries: form.beneficiaries)
    }

    // MARK: - Following

    private func followNames() {
        nameFollower?.follow(Set([form.creditorUuid] + form.beneficiaries.map(\.userUuid)), callBacks: callBacks)
    }

    private func searchUsers() {
        let users = groupId.map { callBacks.users(matching: searchQuery, inGroup: $0) } ?? callBacks.users(matching: searchQuery)

        usersTask.replace(
            with: Task { [weak self] in
                for await users in users {
                    guard let self else {
                        return
                    }

                    self.users = users.sortedByName(selfUuid: self.selfUuid, uuidOf: \.id, nameOf: \.fullName)
                }
            }
        )
    }
}

/// How much of the people a picker lists are selected, as a "Select All" shows it.
enum SelectAllState: Equatable {
    case none

    /// Some of the selection, without everybody listed.
    case some

    case all
}

extension [Beneficiary] {
    /// The shares named as amounts by hand, by the beneficiary's user UUID.
    fileprivate var fixedShares: [String: String] {
        Dictionary(filter(\.isFixedAmount).map { ($0.userUuid, $0.share) }, uniquingKeysWith: { first, _ in first })
    }

    /// The percentages the shares were set as, by the beneficiary's user UUID.
    fileprivate var sharePercentages: [String: String] {
        Dictionary(
            compactMap { beneficiary in beneficiary.sharePercentage.map { (beneficiary.userUuid, $0) } },
            uniquingKeysWith: { first, _ in first }
        )
    }

    /// The weights the shares were set as by hand, by the beneficiary's user UUID: not the `1` the
    /// library states for who was given none, which would keep the expense using weights once
    /// nobody set one any more.
    fileprivate var shareWeightsSetByHand: [String: String] {
        Dictionary(
            filter(\.isShareFixed).compactMap { beneficiary in beneficiary.shareWeight.map { (beneficiary.userUuid, $0) } },
            uniquingKeysWith: { first, _ in first }
        )
    }
}
