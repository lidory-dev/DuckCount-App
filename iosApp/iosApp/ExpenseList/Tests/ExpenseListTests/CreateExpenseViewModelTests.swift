import Foundation
import Testing

@testable import ExpenseList

@MainActor
struct CreateExpenseViewModelTests {
    /// In English, whatever the machine runs: the fields show their amounts with its decimal
    /// separator, see `amountFieldText`.
    private func newViewModel(_ callBacks: FakeExpenseCallBacks = FakeExpenseCallBacks(), locale: Locale = english) -> CreateExpenseViewModel {
        CreateExpenseViewModel(callBacks: callBacks, locale: locale)
    }

    private func shares(_ viewModel: CreateExpenseViewModel) -> [String: String] {
        Dictionary(uniqueKeysWithValues: viewModel.form.beneficiaries.map { ($0.userUuid, $0.share) })
    }

    // MARK: - Creditor and names

    @Test func theUserPaysANewExpense() async {
        let viewModel = newViewModel()

        #expect(viewModel.form.creditorUuid == me)

        await settle { viewModel.names.name(of: me) != nil }

        #expect(viewModel.names.displayName(of: me) == "Me")
    }

    @Test func thePickerListsTheUserFirstThenTheOthersByName() async {
        let viewModel = newViewModel()

        await settle { !viewModel.users.isEmpty }

        #expect(viewModel.users.map(\.id) == [me, ada, bob])
    }

    // MARK: - Amount

    @Test func anAmountTypedWithACommaIsHeldAsTypedAndStandsForItsAmount() {
        let viewModel = newViewModel()

        viewModel.typeAmount("12,5")

        #expect(viewModel.form.amountText == "12,5")
        #expect(viewModel.form.amount == "12.50")

        viewModel.finishAmount()

        #expect(viewModel.form.amountText == "12.50")
    }

    /// As the decimal pad has it in the user's language, the amount the form keeps being the
    /// library's.
    @Test func leavingTheAmountFieldWritesItWithTheDecimalSeparatorOfTheLanguage() {
        let viewModel = newViewModel(locale: french)

        viewModel.typeAmount("12.5")
        viewModel.finishAmount()

        #expect(viewModel.form.amountText == "12,50")
        #expect(viewModel.form.amount == "12.50")
    }

    @Test func anAmountFieldWrittenWithACommaIsEditedAsTyped() {
        let viewModel = newViewModel(locale: french)
        viewModel.typeAmount("12.5")
        viewModel.finishAmount()

        #expect(viewModel.typeAmount("12,5"))
        #expect(viewModel.form.amount == "12.50")

        viewModel.typeAmount("12,59")
        viewModel.finishAmount()
        viewModel.typeAmount("1259")

        #expect(viewModel.form.amountText == "12")
        #expect(viewModel.form.amount == "12.00")
    }

    @Test func pickingACurrencyWritesTheAmountFieldWithTheDecimalSeparatorOfTheLanguage() {
        let viewModel = newViewModel(locale: french)
        viewModel.typeAmount("12")

        viewModel.selectCurrency(euro)

        #expect(viewModel.form.amountText == "12,00")
    }

    @Test func deletingTheDecimalSeparatorDropsTheDecimals() {
        let viewModel = newViewModel()

        viewModel.typeAmount("12.50")
        viewModel.typeAmount("1250")

        #expect(viewModel.form.amountText == "12")
        #expect(viewModel.form.amount == "12.00")
    }

    @Test func aSeparatorIsRefusedInACurrencyWithoutSubunits() {
        let viewModel = newViewModel()

        viewModel.selectCurrency(yen)
        viewModel.typeAmount("12")

        #expect(!viewModel.typeAmount("12."))
        #expect(viewModel.form.amountText == "12")
    }

    @Test func typingTheAmountSplitsItAgain() {
        let viewModel = newViewModel()

        viewModel.selectBeneficiaries([ada, bob])
        viewModel.typeAmount("10")

        #expect(shares(viewModel) == [ada: "5.00", bob: "5.00"])

        viewModel.typeAmount("10.01")

        #expect(shares(viewModel) == [ada: "5.01", bob: "5.00"])
    }

    /// Rounded one by one, two shares of 60.25 would make 120, short of the 121 the amount rounds to.
    @Test func pickingACurrencyWorksTheSharesOutAgainFromTheAmountItRoundsTo() {
        let viewModel = newViewModel()

        viewModel.selectBeneficiaries([ada, bob])
        viewModel.typeAmount("120.5")
        viewModel.selectCurrency(yen)

        #expect(viewModel.form.amount == "121")
        #expect(viewModel.form.amountText == "121")
        #expect(viewModel.form.beneficiaries.map(\.share) == ["61", "60"])
    }

    // MARK: - Shares

    @Test func aShareIsShownAsTypedUntilItsFieldIsLeft() {
        let viewModel = newViewModel()

        viewModel.selectBeneficiaries([ada, bob])
        viewModel.typeAmount("20")
        viewModel.typeShare(userUuid: ada, text: "5,5")

        let adaRow = viewModel.form.beneficiaries.first { $0.userUuid == ada }!
        let bobRow = viewModel.form.beneficiaries.first { $0.userUuid == bob }!

        #expect(viewModel.shareText(of: adaRow) == "5,5")
        #expect(adaRow.share == "5.50")
        #expect(adaRow.isShareFixed)
        #expect(bobRow.share == "14.50")
        #expect(!bobRow.isShareFixed)

        viewModel.finishShare(userUuid: ada)

        #expect(viewModel.shareText(of: viewModel.form.beneficiaries.first { $0.userUuid == ada }!) == "5.50")
    }

    @Test func theSharesAreShownWithTheDecimalSeparatorOfTheLanguageAndKeptAsTheLibraryWritesThem() {
        let viewModel = newViewModel(locale: french)

        viewModel.selectBeneficiaries([ada, bob])
        viewModel.typeAmount("20")

        func adaText() -> String {
            viewModel.shareText(of: viewModel.form.beneficiaries.first { $0.userUuid == ada }!)
        }

        #expect(adaText() == "10,00")

        // Its last decimal deleted, as the field shows it.
        #expect(viewModel.typeShare(userUuid: ada, text: "10,0"))
        #expect(adaText() == "10,0")
        #expect(shares(viewModel)[ada] == "10.00")

        // Its comma deleted: whole units, as with a dot.
        viewModel.typeShare(userUuid: ada, text: "100")
        #expect(adaText() == "10")
        #expect(shares(viewModel)[ada] == "10.00")

        viewModel.finishShare(userUuid: ada)
        #expect(adaText() == "10,00")
    }

    @Test func aFixedShareStaysAsTheAmountChanges() {
        let viewModel = newViewModel()

        viewModel.selectBeneficiaries([ada, bob])
        viewModel.typeAmount("20")
        viewModel.typeShare(userUuid: ada, text: "5")
        viewModel.typeAmount("30")

        #expect(shares(viewModel) == [ada: "5.00", bob: "25.00"])
    }

    @Test func removingAFixedShareSplitsItAgain() {
        let viewModel = newViewModel()

        viewModel.selectBeneficiaries([ada, bob])
        viewModel.typeAmount("20")
        viewModel.typeShare(userUuid: ada, text: "5")
        viewModel.removeFixedShare(userUuid: ada)

        #expect(shares(viewModel) == [ada: "10.00", bob: "10.00"])
        #expect(viewModel.form.beneficiaries.allSatisfy { !$0.isShareFixed })
    }

    @Test func aFixedShareIsKeptForWhoeverStaysPicked() {
        let viewModel = newViewModel()

        viewModel.selectBeneficiaries([ada, bob])
        viewModel.typeAmount("20")
        viewModel.typeShare(userUuid: ada, text: "5")
        viewModel.selectBeneficiaries([ada, bob, me])

        #expect(shares(viewModel) == [ada: "5.00", bob: "7.50", me: "7.50"])
        #expect(viewModel.form.beneficiaries.first { $0.userUuid == ada }?.isShareFixed == true)
    }

    @Test func theBeneficiariesAreKeptInTheOrderTheyWerePickedAndListedByName() async {
        let viewModel = newViewModel()

        viewModel.selectBeneficiaries([bob, ada, me])

        await settle { viewModel.names.name(of: bob) != nil }

        #expect(viewModel.form.beneficiaries.map(\.userUuid) == [bob, ada, me])
        #expect(viewModel.displayedBeneficiaries.map(\.userUuid) == [me, ada, bob])
    }

    // MARK: - Picker

    @Test func theSelectionStopsAtTheLimit() {
        let callBacks = FakeExpenseCallBacks()
        callBacks.maxBeneficiaries = 2
        let viewModel = newViewModel(callBacks)

        viewModel.beginPickingBeneficiaries()
        viewModel.toggleSelection(userUuid: ada)
        viewModel.toggleSelection(userUuid: bob)
        viewModel.toggleSelection(userUuid: me)

        #expect(viewModel.selectedUuids == [ada, bob])

        viewModel.toggleSelection(userUuid: ada)
        viewModel.toggleSelection(userUuid: me)

        #expect(viewModel.selectedUuids == [bob, me])
    }

    @Test func goingBackFromThePickerChangesNothing() {
        let viewModel = newViewModel()

        viewModel.selectBeneficiaries([ada])
        viewModel.beginPickingBeneficiaries()
        viewModel.toggleSelection(userUuid: bob)
        viewModel.beginPickingBeneficiaries()

        #expect(viewModel.selectedUuids == [ada])
        #expect(viewModel.form.beneficiaries.map(\.userUuid) == [ada])
    }

    @Test func confirmingThePickerMakesItsSelectionTheBeneficiaries() {
        let viewModel = newViewModel()

        viewModel.beginPickingBeneficiaries()
        viewModel.toggleSelection(userUuid: bob)
        viewModel.toggleSelection(userUuid: ada)
        viewModel.confirmSelection()

        #expect(viewModel.form.beneficiaries.map(\.userUuid) == [bob, ada])
    }

    // MARK: - Validation

    @Test func theCreditorAloneIsNoBeneficiary() {
        let viewModel = newViewModel()

        viewModel.selectBeneficiaries([me])
        viewModel.typeAmount("10")

        #expect(viewModel.error == .noBeneficiaryOtherThanCreditor)
        #expect(!viewModel.canSave)
    }

    @Test func noAmountTellsNoErrorButCannotBeSaved() {
        let viewModel = newViewModel()

        viewModel.updateTitle("Lunch")

        #expect(viewModel.error == nil)
        #expect(!viewModel.canSave)
    }

    @Test func whatTheLibraryRefusesCannotBeSaved() {
        let callBacks = FakeExpenseCallBacks()
        callBacks.validationError = .sharesExceedTotalAmount
        let viewModel = newViewModel(callBacks)

        viewModel.updateTitle("Lunch")
        viewModel.selectBeneficiaries([me, ada])
        viewModel.typeAmount("10")

        #expect(viewModel.error == .sharesExceedTotalAmount)
        #expect(!viewModel.canSave)
    }

    @Test func anExpenseWithATitleAnAmountAndSharesCanBeSaved() {
        let viewModel = newViewModel()

        viewModel.updateTitle("Lunch")
        viewModel.selectBeneficiaries([me, ada])
        viewModel.typeAmount("10")

        #expect(viewModel.canSave)
    }

    @Test func aTitleOfNothingButSpacesCannotBeSaved() {
        let viewModel = newViewModel()

        viewModel.updateTitle("   ")
        viewModel.selectBeneficiaries([me, ada])
        viewModel.typeAmount("10")

        #expect(!viewModel.canSave)
    }

    @Test func aTitlePastItsLimitByTheWhitespaceAroundItAloneCanBeSaved() {
        let viewModel = newViewModel()

        viewModel.updateTitle("  " + String(repeating: "a", count: 50) + "  ")
        viewModel.selectBeneficiaries([me, ada])
        viewModel.typeAmount("10")

        #expect(viewModel.form.title.hasSuffix("  "))
        #expect(viewModel.canSave)
    }

    /// Counted in UTF-16 units, as Android counts it and a receiving device checks it.
    @Test func aTitleOfEmojisPastItsLimitInUtf16UnitsCannotBeSaved() {
        let viewModel = newViewModel()

        viewModel.updateTitle(String(repeating: "🦆", count: 26))
        viewModel.selectBeneficiaries([me, ada])
        viewModel.typeAmount("10")

        #expect(viewModel.titleLength == 52)
        #expect(!viewModel.canSave)
    }

    @Test func aDescriptionPastItsLimitCannotBeSaved() {
        let viewModel = newViewModel()

        viewModel.updateTitle("Lunch")
        viewModel.updateDescription(String(repeating: "a", count: 501))
        viewModel.selectBeneficiaries([me, ada])
        viewModel.typeAmount("10")

        #expect(!viewModel.canSave)
    }

    // MARK: - Saving

    @Test func savingCreatesTheExpenseTrimmedByTheSharedModuleAndCloses() async {
        let callBacks = FakeExpenseCallBacks()
        let viewModel = newViewModel(callBacks)

        viewModel.updateTitle("Lunch ")
        viewModel.selectBeneficiaries([me, ada])
        viewModel.typeAmount("10")
        viewModel.save()

        await settle { viewModel.isSaved }

        #expect(viewModel.isSaved)
        #expect(callBacks.created.count == 1)
        #expect(callBacks.created.first?.amount == "10.00")
        #expect(callBacks.created.first?.creditorUuid == me)
        #expect(callBacks.created.first?.beneficiaries.map(\.share) == ["5.00", "5.00"])
        // Dated by the shared module, which alone writes it as every device reads it back.
        #expect(callBacks.created.first?.timestamp == callBacks.now)
    }

    @Test func aFailedSaveIsToldAndKeepsTheForm() async {
        let callBacks = FakeExpenseCallBacks()
        callBacks.saveFailure = .write(.noInternet)
        let viewModel = newViewModel(callBacks)

        viewModel.updateTitle("Lunch")
        viewModel.selectBeneficiaries([me, ada])
        viewModel.typeAmount("10")
        viewModel.save()

        await settle { viewModel.saveError != nil }

        #expect(viewModel.saveError == .write(.noInternet))
        #expect(!viewModel.isSaved)
        #expect(!viewModel.isSaving)
        #expect(viewModel.canSave)
    }

    @Test func whoDeletedTheirAccountLeavesTheSelectionAndTheSharesAreWorkedOutAgain() async {
        let callBacks = FakeExpenseCallBacks()
        callBacks.saveFailure = .beneficiariesDeleted([DeletedBeneficiary(userUuid: bob, fullName: "Bob")])
        let viewModel = newViewModel(callBacks)

        viewModel.updateTitle("Lunch")
        viewModel.selectBeneficiaries([me, ada, bob])
        viewModel.typeAmount("30")
        viewModel.save()

        await settle { viewModel.saveError != nil }

        #expect(viewModel.form.beneficiaries.map(\.userUuid) == [me, ada])
        #expect(shares(viewModel) == [me: "15.00", ada: "15.00"])
        #expect(viewModel.saveError?.message.contains("Bob") == true)
    }

    // MARK: - Editing

    @Test func anEditIsReadIntoTheFormAndSavesNothingUntilItChanges() async {
        let callBacks = FakeExpenseCallBacks()
        callBacks.storedExpenses["expense"] = expense()
        let viewModel = CreateExpenseViewModel(callBacks: callBacks, expenseId: "expense", locale: english)

        #expect(viewModel.isBeingRead)

        await settle { !viewModel.isBeingRead }

        #expect(viewModel.form.title == "Lunch")
        #expect(viewModel.form.amountText == "20.00")
        #expect(shares(viewModel) == [me: "10.00", ada: "10.00"])
        #expect(!viewModel.hasChanges)
        #expect(!viewModel.canSave)

        viewModel.updateTitle("Lunch  ")

        #expect(!viewModel.hasChanges)

        viewModel.updateTitle("Dinner")

        #expect(viewModel.hasChanges)
        #expect(viewModel.canSave)

        viewModel.save()

        await settle { viewModel.isSaved }

        #expect(callBacks.updated.first?.id == "expense")
        #expect(callBacks.updated.first?.timestamp == "2026-11-10T12:00:00")
        #expect(callBacks.updated.first?.revision == 1)
    }

    @Test func pickingTheSameCurrencyAgainIsNoChange() async {
        let callBacks = FakeExpenseCallBacks()
        callBacks.storedExpenses["expense"] = expense()
        let viewModel = CreateExpenseViewModel(callBacks: callBacks, expenseId: "expense")

        await settle { !viewModel.isBeingRead }

        viewModel.selectCurrency(Currency(id: "another record", code: "EUR", symbol: "€", hasSubunits: true))

        #expect(!viewModel.hasChanges)
    }
}

private let english = Locale(identifier: "en_GB")
private let french = Locale(identifier: "fr_FR")
