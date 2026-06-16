import SwiftUI

public struct CreateExpenseView<ViewModel: CreateExpenseFormState>: View {
    @ObservedObject private var viewModel: ViewModel
    private let onCancel: () -> Void
    private let onSave: () -> Void

    public init(
        viewModel: ViewModel,
        onCancel: @escaping () -> Void,
        onSave: @escaping () -> Void
    ) {
        self.viewModel = viewModel
        self.onCancel = onCancel
        self.onSave = onSave
    }

    public var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Mandatory", text: $viewModel.title)
                        .textInputAutocapitalization(.sentences)
                } header: {
                    Text("Title")
                } footer: {
                    HStack {
                        Spacer()
                        Text("\(viewModel.title.count) / \(viewModel.maxTitleLength)")
                            .monospacedDigit()
                            .foregroundStyle(
                                viewModel.title.count > viewModel.maxTitleLength
                                    ? Color.red
                                    : Color.secondary
                            )
                            .contentTransition(.numericText())
                            .animation(.snappy, value: viewModel.title.count)
                    }
                }

                Section("Paid by") {
                    NavigationLink {
                        CreditorPickerView(viewModel: viewModel)
                    } label: {
                        Text(viewModel.creditor?.fullName ?? "Select")
                            .foregroundStyle(viewModel.creditor == nil ? .secondary : .primary)
                    }
                }

                Section("Amount") {
                    HStack {
                        TextField("0", text: amountBinding)
                            .keyboardType(.decimalPad)
                        Menu {
                            ForEach(viewModel.currencies) { currency in
                                Button {
                                    viewModel.selectCurrency(currency)
                                } label: {
                                    if currency.id == viewModel.currency.id {
                                        Label("\(currency.name) (\(currency.symbol))", systemImage: "checkmark")
                                    } else {
                                        Text("\(currency.name) (\(currency.symbol))")
                                    }
                                }
                            }
                        } label: {
                            HStack(spacing: 4) {
                                Text(viewModel.currency.symbol)
                                    .font(.body.weight(.semibold))
                                Image(systemName: "chevron.down")
                                    .font(.caption2)
                            }
                            .foregroundStyle(.tint)
                        }
                    }
                }

                Section {
                    TextEditor(text: $viewModel.description)
                        .overlay(alignment: .topLeading) {
                            if viewModel.description.isEmpty {
                                Text("Optional")
                                    .foregroundStyle(Color(uiColor: .placeholderText))
                                    .padding(.top, 8)
                                    .padding(.leading, 5)
                                    .allowsHitTesting(false)
                            }
                        }
                } header: {
                    Text("Description")
                } footer: {
                    HStack {
                        Spacer()
                        Text("\(viewModel.description.count) / \(viewModel.maxDescriptionLength)")
                            .monospacedDigit()
                            .foregroundStyle(
                                viewModel.description.count > viewModel.maxDescriptionLength
                                    ? Color.red
                                    : Color.secondary
                            )
                            .contentTransition(.numericText())
                            .animation(.snappy, value: viewModel.description.count)
                    }
                }

                Section {
                    if let error = viewModel.error, error != .otherKnownError {
                        Label(error.message, systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.red)
                            .padding(.top, 4)
                            .contentTransition(.opacity)
                            .transition(.opacity.combined(with: .move(edge: .top)))
                    }

                    NavigationLink {
                        BeneficiariesPickerView(viewModel: viewModel)
                    } label: {
                        Label(
                            viewModel.beneficiaries.isEmpty ? "Add beneficiaries" : "Modify beneficiaries",
                            systemImage: "person.2.badge.plus"
                        )
                    }
                    if viewModel.beneficiaries.isEmpty {
                        Text("No beneficiaries selected")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(viewModel.beneficiaries) { beneficiary in
                            HStack {
                                Text(beneficiary.fullName)
                                Spacer()
                                TextField(
                                    "0",
                                    text: Binding(
                                        get: { beneficiary.share },
                                        set: { newValue in
                                            viewModel.updateBeneficiaryShare(
                                                userUuid: beneficiary.userUuid,
                                                share: newValue
                                            )
                                        }
                                    )
                                )
                                .keyboardType(.decimalPad)
                                .multilineTextAlignment(.trailing)
                                .frame(maxWidth: 100)
                                Text(viewModel.currency.symbol)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                } header: {
                    Text("Beneficiaries")
                }
            }
            .animation(.easeInOut(duration: 0.25), value: viewModel.error)
            .navigationTitle("New Expense")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: onSave)
                        .disabled(!canSave)
                }
            }
        }
    }

    private var canSave: Bool {
        viewModel.error == nil
            && !viewModel.title.trimmingCharacters(in: .whitespaces).isEmpty
            && (1...viewModel.maxTitleLength) ~= viewModel.title.count
            && (0...viewModel.maxDescriptionLength) ~= viewModel.description.count
            && !viewModel.amount.trimmingCharacters(in: .whitespaces).isEmpty
    }

    private var amountBinding: Binding<String> {
        Binding(
            get: { viewModel.amount },
            set: { viewModel.updateAmount($0) }
        )
    }
}
