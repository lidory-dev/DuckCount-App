import SwiftUI

/// An expense: what it was for, who paid it and who shares it, and what its creditor can do with it,
/// as Android's `ExpenseDetailScreen`.
struct ExpenseDetailView: View {
    private let callBacks: any ExpenseCallBacks
    private let onClose: () -> Void

    @StateObject private var viewModel: ExpenseDetailViewModel

    @State private var isEditing = false
    @State private var isConfirmingDeletion = false

    init(expenseId: String, callBacks: any ExpenseCallBacks, onClose: @escaping () -> Void) {
        self.callBacks = callBacks
        self.onClose = onClose
        _viewModel = StateObject(wrappedValue: ExpenseDetailViewModel(expenseId: expenseId, callBacks: callBacks))
    }

    var body: some View {
        Group {
            if let expense = viewModel.expense, let actions = viewModel.actions {
                content(expense: expense, actions: actions)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(viewModel.expense.flatMap { LocalDateTime.date(from: $0.timestamp) }?.shortDayAndTimeTitle ?? "")
        .navigationBarTitleDisplayMode(.inline)
        // Leaving would cancel the write, possibly after the server took it.
        .navigationBarBackButtonHidden(viewModel.isBusy)
        .toolbar {
            if viewModel.actions?.canEdit == true {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        isEditing = true
                    } label: {
                        Text("Edit", bundle: .module)
                    }
                    .disabled(viewModel.isBusy)
                }
            }
        }
        .sheet(isPresented: $isEditing) {
            CreateExpenseView(callBacks: callBacks, expenseId: viewModel.expenseId)
        }
        .confirmationDialog(
            Text("Are you sure you want to delete this expense?", bundle: .module),
            isPresented: $isConfirmingDeletion,
            titleVisibility: .visible
        ) {
            Button(role: .destructive, action: viewModel.delete) {
                Text("Delete Expense", bundle: .module)
            }
        } message: {
            Text("Unlike an expense in a group, it cannot be restored.", bundle: .module)
        }
        .alert(
            viewModel.error?.message ?? "",
            isPresented: Binding(get: { viewModel.error != nil }, set: { if !$0 { viewModel.error = nil } })
        ) {
            Button(role: .cancel) {} label: {
                Text("OK", bundle: .module)
            }
        }
        .onChange(of: viewModel.isClosed) { _, isClosed in
            if isClosed {
                onClose()
            }
        }
    }

    private func content(expense: Expense, actions: ExpenseActions) -> some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 6) {
                    Text(expense.title)
                        .font(.title2.bold())

                    if !expense.description.isBlank {
                        Text(expense.description)
                            .foregroundStyle(.secondary)
                    }

                    if expense.isSettled {
                        SettledLabel(settledAt: expense.settledAt)
                            .font(.subheadline)
                            .padding(.top, 4)
                    }
                }
                .padding(.vertical, 4)
            }

            Section {
                PersonAmountRow(
                    userUuid: expense.creditorUuid,
                    name: viewModel.names.displayName(of: expense.creditorUuid),
                    amount: expense.amount,
                    currency: expense.currency
                )
            } header: {
                Text("Paid by", bundle: .module)
            }

            Section {
                ForEach(viewModel.beneficiaries, id: \.userUuid) { beneficiary in
                    PersonAmountRow(
                        userUuid: beneficiary.userUuid,
                        name: viewModel.names.displayName(of: beneficiary.userUuid),
                        amount: beneficiary.share,
                        currency: expense.currency
                    )
                }
            } header: {
                Text("Beneficiaries", bundle: .module)
            }

            if actions.canSettle {
                Section {
                    Button(action: viewModel.toggleSettled) {
                        HStack {
                            if expense.isSettled {
                                Label {
                                    Text("Mark as Not Settled", bundle: .module)
                                } icon: {
                                    Image(systemName: "arrow.uturn.backward.circle")
                                }
                            } else {
                                Label {
                                    Text("Mark as Settled", bundle: .module)
                                } icon: {
                                    Image(systemName: "checkmark.circle")
                                }
                            }

                            Spacer()

                            if viewModel.isSettling {
                                ProgressView()
                            }
                        }
                    }
                    .disabled(viewModel.isBusy)
                }
            }

            if actions.canShowHistory {
                Section {
                    NavigationLink {
                        ExpenseHistoryView(expenseId: expense.id, callBacks: callBacks)
                    } label: {
                        Label {
                            Text("History", bundle: .module)
                        } icon: {
                            Image(systemName: "clock.arrow.circlepath")
                        }
                    }
                    .disabled(viewModel.isBusy)
                }
            }

            if actions.canDelete {
                Section {
                    Button(role: .destructive) {
                        isConfirmingDeletion = true
                    } label: {
                        HStack {
                            Label {
                                Text("Delete Expense", bundle: .module)
                            } icon: {
                                Image(systemName: "trash")
                            }
                            // Its icon too, which the list would tint otherwise.
                            .foregroundStyle(.red)

                            Spacer()

                            if viewModel.isDeleting {
                                ProgressView()
                            }
                        }
                    }
                    .disabled(viewModel.isBusy)
                }
            }
        }
    }
}

#Preview {
    NavigationStack {
        ExpenseDetailView(expenseId: "1", callBacks: PreviewExpenseCallBacks(), onClose: {})
    }
}
