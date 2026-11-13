import SwiftUI

/// The form a new one-time expense is made with, or one is edited with, as Android's
/// `CreateExpenseScreen`: what it was for, its amount and who shares it, each share worked out by
/// the library from what the others are fixed at. Shown in a sheet of its own.
struct CreateExpenseView: View {
    @StateObject private var viewModel: CreateExpenseViewModel

    @State private var isPickingBeneficiaries = false
    @State private var isConfirmingDiscard = false

    @FocusState private var focusedField: Field?

    @Environment(\.dismiss) private var dismiss

    private enum Field: Hashable {
        case title
        case amount
        case description
        case share(userUuid: String)
    }

    init(callBacks: any ExpenseCallBacks, expenseId: String? = nil) {
        _viewModel = StateObject(wrappedValue: CreateExpenseViewModel(callBacks: callBacks, expenseId: expenseId))
    }

    var body: some View {
        NavigationStack {
            Form {
                titleSection
                paidBySection
                amountSection
                descriptionSection
                beneficiariesSection
            }
            .disabled(viewModel.isSaving || viewModel.isBeingRead)
            .navigationTitle(
                viewModel.isNewExpense ? Text("New Expense", bundle: .module) : Text("Edit Expense", bundle: .module)
            )
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbar }
            .navigationDestination(isPresented: $isPickingBeneficiaries) {
                BeneficiaryPickerView(viewModel: viewModel)
            }
            .animation(.default, value: viewModel.error)
        }
        // Leaving would cancel the write, possibly after the server took it, or lose what was typed.
        .interactiveDismissDisabled(viewModel.isSaving || viewModel.hasEdits)
        .confirmationDialog(
            Text("Discard Changes", bundle: .module),
            isPresented: $isConfirmingDiscard
        ) {
            Button(role: .destructive) {
                dismiss()
            } label: {
                Text("Discard Changes", bundle: .module)
            }

            Button(role: .cancel) {} label: {
                Text("Keep Editing", bundle: .module)
            }
        }
        .alert(
            viewModel.saveError?.message ?? "",
            isPresented: Binding(get: { viewModel.saveError != nil }, set: { if !$0 { viewModel.saveError = nil } })
        ) {
            Button(role: .cancel) {} label: {
                Text("OK", bundle: .module)
            }
        }
        .onChange(of: viewModel.isSaved) { _, isSaved in
            if isSaved {
                dismiss()
            }
        }
        .onChange(of: focusedField) { previous, _ in
            // Once a field is left, its text is written as the amount it stands for.
            switch previous {
            case .amount:
                viewModel.finishAmount()
            case .share(let userUuid):
                viewModel.finishShare(userUuid: userUuid)
            default:
                break
            }
        }
        .onAppear {
            if viewModel.isNewExpense {
                focusedField = .title
            }
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .cancellationAction) {
            Button {
                if viewModel.hasEdits {
                    isConfirmingDiscard = true
                } else {
                    dismiss()
                }
            } label: {
                Text("Cancel", bundle: .module)
            }
            .disabled(viewModel.isSaving)
        }

        ToolbarItem(placement: .confirmationAction) {
            if viewModel.isSaving {
                ProgressView()
            } else {
                Button {
                    focusedField = nil
                    viewModel.save()
                } label: {
                    viewModel.isNewExpense ? Text("Add", bundle: .module) : Text("Save", bundle: .module)
                }
                .disabled(!viewModel.canSave)
            }
        }

        // The decimal pad has no key to leave a field with.
        ToolbarItemGroup(placement: .keyboard) {
            Spacer()

            Button {
                focusedField = nil
            } label: {
                Text("Done", bundle: .module)
            }
        }
    }

    private var titleSection: some View {
        Section {
            TextField(
                String(localized: "Title", bundle: .module),
                text: Binding(get: { viewModel.form.title }, set: viewModel.updateTitle)
            )
            .textInputAutocapitalization(.sentences)
            .submitLabel(.next)
            .focused($focusedField, equals: .title)
            .onSubmit { focusedField = .amount }
        } footer: {
            // Counted as it is saved, without the whitespace around it, as Save counts it.
            LengthCounter(length: viewModel.titleLength, maxLength: viewModel.maxTitleLength)
        }
    }

    private var paidBySection: some View {
        Section {
            LabeledContent {
                Text(viewModel.names.displayName(of: viewModel.form.creditorUuid))
            } label: {
                Text("Paid by", bundle: .module)
            }
        }
    }

    private var amountSection: some View {
        Section {
            HStack {
                AmountTextField(
                    String(localized: "Amount", bundle: .module),
                    text: viewModel.form.amountText,
                    type: viewModel.typeAmount
                )
                .focused($focusedField, equals: .amount)

                currencyMenu
            }
        }
    }

    private var currencyMenu: some View {
        Menu {
            Picker(
                selection: Binding(
                    get: { viewModel.form.currency.code },
                    set: { code in
                        if let currency = viewModel.currencies.first(where: { $0.code == code }) {
                            viewModel.selectCurrency(currency)
                        }
                    }
                )
            ) {
                ForEach(viewModel.currencies, id: \.code) { currency in
                    Text(verbatim: "\(viewModel.currencyDisplayName(currency)) (\(currency.symbol))")
                        .tag(currency.code)
                }
            } label: {
                EmptyView()
            }
        } label: {
            HStack(spacing: 4) {
                Text(viewModel.form.currency.symbol)
                    .fontWeight(.semibold)

                Image(systemName: "chevron.up.chevron.down")
                    .font(.caption2)
            }
        }
        .accessibilityLabel(Text("Currency", bundle: .module))
        .accessibilityValue(viewModel.currencyDisplayName(viewModel.form.currency))
    }

    private var descriptionSection: some View {
        Section {
            TextField(
                String(localized: "Description (optional)", bundle: .module),
                text: Binding(get: { viewModel.form.description }, set: viewModel.updateDescription),
                axis: .vertical
            )
            .lineLimit(1...5)
            .focused($focusedField, equals: .description)
        } footer: {
            LengthCounter(length: viewModel.descriptionLength, maxLength: viewModel.maxDescriptionLength)
        }
    }

    private var beneficiariesSection: some View {
        Section {
            if let error = viewModel.error {
                Label {
                    Text(error.message)
                } icon: {
                    Image(systemName: "exclamationmark.triangle.fill")
                }
                .foregroundStyle(.red)
                .transition(.opacity)
            }

            ForEach(viewModel.displayedBeneficiaries, id: \.userUuid) { beneficiary in
                shareRow(beneficiary)
            }

            Button {
                focusedField = nil
                viewModel.beginPickingBeneficiaries()
                isPickingBeneficiaries = true
            } label: {
                if viewModel.form.beneficiaries.isEmpty {
                    Label {
                        Text("Add Beneficiaries", bundle: .module)
                    } icon: {
                        Image(systemName: "person.badge.plus")
                    }
                } else {
                    Label {
                        Text("Edit Beneficiaries", bundle: .module)
                    } icon: {
                        Image(systemName: "person.2")
                    }
                }
            }
        } header: {
            Text(
                "Beneficiaries (\(viewModel.form.beneficiaries.count)/\(viewModel.maxBeneficiaries))",
                bundle: .module
            )
        }
    }

    private func shareRow(_ beneficiary: Beneficiary) -> some View {
        HStack(spacing: 8) {
            Text(viewModel.names.displayName(of: beneficiary.userUuid))
                .lineLimit(1)

            if beneficiary.isShareFixed {
                Button {
                    viewModel.removeFixedShare(userUuid: beneficiary.userUuid)
                } label: {
                    Image(systemName: "arrow.uturn.backward.circle")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(Text("Remove fixed share", bundle: .module))
            }

            Spacer(minLength: 8)

            AmountTextField(
                "0",
                text: viewModel.shareText(of: beneficiary),
                type: { viewModel.typeShare(userUuid: beneficiary.userUuid, text: $0) }
            )
            .multilineTextAlignment(.trailing)
            .monospacedDigit()
            .frame(maxWidth: 110)
            .focused($focusedField, equals: .share(userUuid: beneficiary.userUuid))

            Text(viewModel.form.currency.symbol)
                .foregroundStyle(.secondary)
        }
    }
}

/// A field an amount is typed in, which takes only what `type` does, see `isAmountInput`: anything
/// else is taken back as soon as it shows, the field keeping its text and its cursor. A field shows
/// what is typed until the text it is bound to changes, which a refusal leaves as it was: so it holds
/// a text of its own, and puts the one it had back once the refused one is drawn.
private struct AmountTextField: View {
    private let placeholder: String
    private let text: String
    private let type: (String) -> Bool

    @State private var shown: String

    init(_ placeholder: String, text: String, type: @escaping (String) -> Bool) {
        self.placeholder = placeholder
        self.text = text
        self.type = type
        _shown = State(initialValue: text)
    }

    var body: some View {
        TextField(placeholder, text: $shown)
            .keyboardType(.decimalPad)
            .onChange(of: text) { _, text in
                // Written anew by the form: once left, worked out again, or another currency.
                if shown != text {
                    shown = text
                }
            }
            .onChange(of: shown) { previous, typed in
                guard typed != text else {
                    return
                }

                if !type(typed) {
                    shown = previous
                }
            }
    }
}

/// How long a field's text is against the most it takes, in red once past it.
private struct LengthCounter: View {
    let length: Int
    let maxLength: Int

    var body: some View {
        HStack {
            Spacer()

            Text(verbatim: "\(length)/\(maxLength)")
                .monospacedDigit()
                .foregroundStyle(length > maxLength ? Color.red : Color.secondary)
                .contentTransition(.numericText())
                .animation(.snappy, value: length)
        }
    }
}

/// Who shares the expense, picked from the user's contacts and themself, as Android's
/// `BeneficiariesDialog`, up to the most an expense may list: the selection is the expense's once
/// done, and dropped when going back.
struct BeneficiaryPickerView: View {
    @ObservedObject var viewModel: CreateExpenseViewModel

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            Section {
                ForEach(viewModel.users) { user in
                    row(user)
                }
            } header: {
                Text("Selected: \(viewModel.selectedUuids.count)/\(viewModel.maxBeneficiaries)", bundle: .module)
            }
        }
        .navigationTitle(Text("Beneficiaries", bundle: .module))
        .navigationBarTitleDisplayMode(.inline)
        .searchable(
            text: Binding(get: { viewModel.searchQuery }, set: viewModel.search),
            placement: .navigationBarDrawer(displayMode: .always)
        )
        .overlay {
            if viewModel.users.isEmpty, !viewModel.searchQuery.isEmpty {
                ContentUnavailableView.search(text: viewModel.searchQuery)
            }
        }
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button {
                    viewModel.confirmSelection()
                    dismiss()
                } label: {
                    Text("Done", bundle: .module)
                }
            }
        }
    }

    private func row(_ user: User) -> some View {
        let isSelected = viewModel.selectedUuids.contains(user.id)
        // A selected row stays enabled so that it can still be unselected once the selection is full.
        let isDisabled = !isSelected && viewModel.selectedUuids.count >= viewModel.maxBeneficiaries

        return Button {
            viewModel.toggleSelection(userUuid: user.id)
        } label: {
            // Colors rather than the hierarchical styles, which a button's label takes from its tint.
            HStack(spacing: 12) {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(isSelected ? AnyShapeStyle(.tint) : AnyShapeStyle(Color.secondary))

                PersonAvatar(userUuid: user.id, name: user.fullName)

                Text(user.fullName)
                    .foregroundStyle(Color.primary)
                    .lineLimit(1)
            }
            .opacity(isDisabled ? 0.4 : 1)
        }
        .disabled(isDisabled)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

#Preview {
    CreateExpenseView(callBacks: PreviewExpenseCallBacks())
}
