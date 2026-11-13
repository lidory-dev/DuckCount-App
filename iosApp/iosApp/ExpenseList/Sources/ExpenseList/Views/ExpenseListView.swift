import SwiftUI

/// The user's one-time expenses, newest first under the day each happened on, which open on their
/// own screen, and the way to add one: the Expenses tab, as Android's `ExpenseListScreen`.
public struct ExpenseListView: View {
    private let callBacks: any ExpenseCallBacks

    @StateObject private var viewModel: ExpenseListViewModel

    @State private var selection: String?
    @State private var isCreating = false

    @Environment(\.scenePhase) private var scenePhase

    public init(callBacks: any ExpenseCallBacks) {
        self.callBacks = callBacks
        _viewModel = StateObject(wrappedValue: ExpenseListViewModel(callBacks: callBacks))
    }

    public var body: some View {
        NavigationSplitView {
            list
                .navigationTitle(Text("Expenses", bundle: .module))
                .toolbar {
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
        } detail: {
            NavigationStack {
                if let selection {
                    ExpenseDetailView(expenseId: selection, callBacks: callBacks) {
                        self.selection = nil
                    }
                    .id(selection)
                } else {
                    ContentUnavailableView {
                        Label {
                            Text("No Expense Selected", bundle: .module)
                        } icon: {
                            Image(systemName: "receipt")
                        }
                    }
                }
            }
        }
        .sheet(isPresented: $isCreating) {
            CreateExpenseView(callBacks: callBacks)
        }
        .task {
            await viewModel.refreshOnce()
        }
        // No push reaches iOS yet, so coming back to the app is when what changed meanwhile is read.
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task {
                    await viewModel.refresh()
                }
            }
        }
    }

    private var list: some View {
        List(selection: $selection) {
            ForEach(viewModel.days) { day in
                Section {
                    ForEach(day.expenses) { expense in
                        NavigationLink(value: expense.id) {
                            ExpenseRow(
                                expense: expense,
                                creditorName: viewModel.names.displayName(of: expense.creditorUuid),
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
                    Button {
                        isCreating = true
                    } label: {
                        Text("New Expense", bundle: .module)
                    }
                }
            }
        }
    }
}

/// An expense in the list: what it was for and who paid, what it came to, and what the user is owed
/// of it or owes, or that it was paid back.
struct ExpenseRow: View {
    let expense: Expense
    let creditorName: String
    let selfUuid: String?

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(expense.title)
                    .font(.headline)
                    .lineLimit(1)

                Text("Paid by \(creditorName)", bundle: .module)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 8)

            VStack(alignment: .trailing, spacing: 2) {
                Text(expense.amount.formattedAmount(in: expense.currency))
                    .font(.headline)
                    .monospacedDigit()
                    .lineLimit(1)

                // Nobody owes anything of it any more.
                if expense.isSettled {
                    SettledLabel()
                        .font(.subheadline)
                        .labelStyle(.titleAndIcon)
                        .lineLimit(1)
                } else if let balance = expense.personalBalance(selfUuid: selfUuid) {
                    Text(balance.formatted(in: expense.currency, withSign: true))
                        .font(.subheadline)
                        .monospacedDigit()
                        .foregroundStyle(expense.role(selfUuid: selfUuid).color)
                        .lineLimit(1)
                }
            }
            .layoutPriority(1)
        }
        .padding(.vertical, 4)
    }
}

#Preview {
    ExpenseListView(callBacks: PreviewExpenseCallBacks())
}
