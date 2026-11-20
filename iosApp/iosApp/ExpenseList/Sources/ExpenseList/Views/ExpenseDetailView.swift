import AppColors
import AppFonts
import SwiftUI

/// An expense: what it was for, who paid it and who shares it, and what the user can do with it, as
/// Android's `ExpenseDetailScreen`. A deleted expense of a group offers its restoration and its
/// history alone, and says who deleted it.
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
                ExpenseDetailSkeleton()
            }
        }
        .navigationTitle(viewModel.expense.flatMap { LocalDateTime.date(from: $0.timestamp) }?.shortDayAndTimeTitle ?? "")
        .navigationBarTitleDisplayMode(.inline)
        // Leaving would cancel the write, possibly after the server took it.
        .navigationBarBackButtonHidden(viewModel.isBusy)
        .toolbar {
            // A deleted expense is restored rather than edited.
            if viewModel.actions?.canEdit == true, viewModel.expense?.isDeleted == false {
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
            CreateExpenseView(callBacks: callBacks, groupId: viewModel.expense?.groupId, expenseId: viewModel.expenseId)
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

    private var deletionConfirmationTitle: Text {
        viewModel.expense?.isSettlement == true
            ? Text("Are you sure you want to delete this settlement?", bundle: .module)
            : Text("Are you sure you want to delete this expense?", bundle: .module)
    }

    private var deleteLabelText: Text {
        viewModel.expense?.isSettlement == true
            ? Text("Delete Settlement", bundle: .module)
            : Text("Delete Expense", bundle: .module)
    }

    private func content(expense: Expense, actions: ExpenseActions) -> some View {
        List {
            Section {
                header(expense: expense, actions: actions)
            }
            .appCardRows()

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
            .appCardRows()

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
            .appCardRows()

            if actions.canSettle {
                settleSection(expense: expense)
            }

            if expense.isDeleted, actions.canRestore {
                restoreSection
            }

            // A deleted expense's edits are still read: deleting it made none of them, its screen
            // naming who did.
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
                .appCardRows()
            }

            if !expense.isDeleted, actions.canDelete {
                deleteSection(expense: expense)
            }
        }
        .appListBackground()
    }

    /// What it was for, or that it is a settlement, which has no title, and what became of it.
    private func header(expense: Expense, actions: ExpenseActions) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Group {
                if expense.isSettlement {
                    Label {
                        Text("Settlement", bundle: .module)
                    } icon: {
                        Image(systemName: "arrow.left.arrow.right")
                    }
                } else {
                    Text(expense.title)
                }
            }
            .font(.app(.title2))
            .strikethrough(expense.isDeleted)

            if !expense.description.isBlank {
                Text(expense.description)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .strikethrough(expense.isDeleted)
            }

            if expense.isSettled {
                SettledLabel(settledAt: expense.settledAt)
                    .font(.app(.subheadline))
                    .padding(.top, 4)
            }

            // Deleting it made no version of it, so its history does not say who did.
            if let deletedByUuid = expense.deletedByUuid {
                Text("Deleted by \(viewModel.names.displayName(of: deletedByUuid))", bundle: .module)
                    .font(.app(.subheadline))
                    .italic()
                    .padding(.top, 4)
            }

            // Why it offers no edit, which it would otherwise.
            if actions.isEditLimitReached {
                Text(
                    "This expense can’t be edited anymore: it has reached its \(viewModel.maxGroupExpenseEdits) edits",
                    bundle: .module
                )
                .font(.app(.subheadline))
                .foregroundStyle(AppColors.onSurfaceVariant)
                .padding(.top, 4)
            }
        }
        .padding(.vertical, 4)
    }

    private func settleSection(expense: Expense) -> some View {
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
        .appCardRows()
    }

    private var restoreSection: some View {
        Section {
            Button(action: viewModel.restore) {
                HStack {
                    Label {
                        Text("Restore Expense", bundle: .module)
                    } icon: {
                        Image(systemName: "arrow.uturn.backward")
                    }

                    Spacer()

                    if viewModel.isRestoring {
                        ProgressView()
                    }
                }
            }
            .disabled(viewModel.isBusy)
        }
        .appCardRows()
    }

    private func deleteSection(expense: Expense) -> some View {
        Section {
            Button(role: .destructive) {
                // A plain group expense is deleted at once, any member being able to restore it.
                // A one-time expense or a settlement is not, and is asked about first.
                if expense.isSettlement || !expense.isGroupExpense {
                    isConfirmingDeletion = true
                } else {
                    viewModel.delete()
                }
            } label: {
                HStack {
                    Label {
                        deleteLabelText
                    } icon: {
                        Image(systemName: "trash")
                    }
                    // Its icon too, which the list would tint otherwise.
                    .foregroundStyle(AppColors.error)

                    Spacer()

                    if viewModel.isDeleting {
                        ProgressView()
                    }
                }
            }
            .disabled(viewModel.isBusy)
            // On the button, for the dialog to point at it where it shows as a popover.
            .confirmationDialog(
                deletionConfirmationTitle,
                isPresented: $isConfirmingDeletion,
                titleVisibility: .visible
            ) {
                Button(role: .destructive, action: viewModel.delete) {
                    deleteLabelText
                }
            } message: {
                if expense.isSettlement {
                    Text("Unlike an expense, it cannot be restored, but it will still appear in the list of expenses.", bundle: .module)
                } else {
                    Text("Unlike an expense in a group, it cannot be restored.", bundle: .module)
                }
            }
        }
        .appCardRows()
    }
}

#Preview {
    NavigationStack {
        ExpenseDetailView(expenseId: "1", callBacks: PreviewExpenseCallBacks(), onClose: {})
    }
}

#Preview("Deleted group expense") {
    NavigationStack {
        ExpenseDetailView(expenseId: "g2", callBacks: PreviewExpenseCallBacks(), onClose: {})
    }
}
