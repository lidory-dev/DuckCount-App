import AppColors
import AppFonts
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

    /// The expense of the group the app asks to open, a notification about it having been tapped:
    /// pushed once, then cleared.
    @Binding private var opening: String?

    /// The expense pushed on the app's asking, see `opening`.
    @State private var opened: String?

    @State private var isCreating = false

    /// The date pinned at the top of the list, see `DayPinning`.
    @State private var pinning = DayPinning()

    public init(groupId: String, callBacks: any ExpenseCallBacks, opening: Binding<String?> = .constant(nil)) {
        self.groupId = groupId
        self.callBacks = callBacks
        _opening = opening
        _viewModel = StateObject(wrappedValue: GroupExpenseListViewModel(groupId: groupId, callBacks: callBacks))
    }

    public var body: some View {
        List {
            ForEach(viewModel.days) { day in
                Section {
                    // A row, which `pinnedDayOverlay` pins in its place, see `DayPinning`.
                    ExpenseDayHeader(title: day.title)
                        .dateRow(of: day, in: pinning)

                    ForEach(day.expenses) { expense in
                        ExpenseRow(
                            expense: expense,
                            creditorName: viewModel.names.displayName(of: expense.creditorUuid),
                            recipientName: expense.settlementRecipientUuid.map(viewModel.names.displayName(of:)) ?? "",
                            selfUuid: viewModel.selfUuid
                        )
                        // Under the card, which shows no chevron, as Android's card has none.
                        .background {
                            NavigationLink {
                                PushedExpenseDetailView(expenseId: expense.id, callBacks: callBacks)
                            } label: {
                                EmptyView()
                            }
                            .opacity(0)
                        }
                        // Inside the card's row settings, which a list's row takes no notice of otherwise.
                        .placed(in: pinning, rowId: expense.id, dayId: day.id, isDate: false)
                        .expenseCard(isDeleted: expense.isDeleted)
                        .onAppear { viewModel.rowAppeared(expense.id) }
                    }
                }
            }
        }
        .appListBackground()
        .listStyle(.plain)
        .pinnedDayOverlay(pinning)
        .onChange(of: viewModel.days, initial: true) { _, days in
            pinning.setDays(days.map { ($0.id, $0.title) })
        }
        .refreshable {
            await viewModel.refresh()
        }
        .overlay {
            if viewModel.isLoading {
                ExpenseListSkeleton()
            } else if viewModel.days.isEmpty {
                ContentUnavailableView {
                    Label {
                        Text("You don’t have any expenses yet", bundle: .module)
                    } icon: {
                        Image(systemName: "receipt")
                    }
                    .font(.app(.title2))
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
        .navigationDestination(item: $opened) { expenseId in
            PushedExpenseDetailView(expenseId: expenseId, callBacks: callBacks)
        }
        .onChange(of: opening, initial: true) { _, expenseId in
            if let expenseId {
                opened = expenseId
                opening = nil
            }
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
