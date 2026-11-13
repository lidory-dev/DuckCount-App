import SwiftUI

/// A group's expenses, newest first under the day each happened on, deleted ones struck through,
/// which open on their own screen, and the way to add one while the user is still in the group, as
/// Android's `ExpenseListScreen` shows them in a group's detail.
///
/// Embedded in the group's screen, inside its navigation stack: it pushes an expense's screen onto
/// that stack and adds its button to the bar, and holds no navigation of its own.
public struct GroupExpensesView: View {
    private let groupId: String
    private let callBacks: any ExpenseCallBacks

    @StateObject private var viewModel: GroupExpenseListViewModel

    @State private var isCreating = false

    public init(groupId: String, callBacks: any ExpenseCallBacks) {
        self.groupId = groupId
        self.callBacks = callBacks
        _viewModel = StateObject(wrappedValue: GroupExpenseListViewModel(groupId: groupId, callBacks: callBacks))
    }

    public var body: some View {
        List {
            ForEach(viewModel.days) { day in
                Section {
                    ForEach(day.expenses) { expense in
                        NavigationLink {
                            PushedExpenseDetailView(expenseId: expense.id, callBacks: callBacks)
                        } label: {
                            ExpenseRow(
                                expense: expense,
                                creditorName: viewModel.names.displayName(of: expense.creditorUuid),
                                recipientName: expense.settlementRecipientUuid.map(viewModel.names.displayName(of:)) ?? "",
                                selfUuid: viewModel.selfUuid
                            )
                        }
                    }
                } header: {
                    Text(day.date?.dayTitle ?? day.id)
                }
            }
        }
        .listStyle(.plain)
        .refreshable {
            await viewModel.refresh()
        }
        .overlay {
            if viewModel.isLoading {
                ProgressView()
            } else if viewModel.days.isEmpty {
                ContentUnavailableView {
                    Label {
                        Text("You don’t have any expenses yet", bundle: .module)
                    } icon: {
                        Image(systemName: "receipt")
                    }
                } actions: {
                    if viewModel.canAddExpense {
                        Button {
                            isCreating = true
                        } label: {
                            Text("New Expense", bundle: .module)
                        }
                    }
                }
            }
        }
        .toolbar {
            // Nothing is added to a group the user is no longer in, which stays read-only.
            if viewModel.canAddExpense {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        isCreating = true
                    } label: {
                        Label {
                            Text("New Expense", bundle: .module)
                        } icon: {
                            Image(systemName: "plus")
                        }
                    }
                }
            }
        }
        .sheet(isPresented: $isCreating) {
            CreateExpenseView(callBacks: callBacks, groupId: groupId)
        }
    }
}

/// An expense's screen pushed onto the group's navigation stack, which it leaves once the expense
/// is deleted or restored.
private struct PushedExpenseDetailView: View {
    let expenseId: String
    let callBacks: any ExpenseCallBacks

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ExpenseDetailView(expenseId: expenseId, callBacks: callBacks) {
            dismiss()
        }
    }
}

#Preview {
    NavigationStack {
        GroupExpensesView(groupId: "trip", callBacks: PreviewExpenseCallBacks())
            .navigationTitle(Text(verbatim: "Trip"))
    }
}
