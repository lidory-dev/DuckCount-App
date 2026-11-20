import AppColors
import AppFonts
import SwiftUI

/// The user's one-time expenses, newest first under the day each happened on, which open on their
/// own screen, and the way to add one: the Expenses tab, as Android's `ExpenseListScreen`.
public struct ExpenseListView: View {
    private let callBacks: any ExpenseCallBacks

    @StateObject private var viewModel: ExpenseListViewModel

    /// The expense the app asks to open, a notification about it having been tapped: opened once,
    /// then cleared, as Android opens the one an intent names.
    @Binding private var opening: String?

    @State private var selection: String?
    @State private var isCreating = false

    @Environment(\.scenePhase) private var scenePhase

    public init(callBacks: any ExpenseCallBacks, opening: Binding<String?> = .constant(nil)) {
        self.callBacks = callBacks
        _opening = opening
        _viewModel = StateObject(wrappedValue: ExpenseListViewModel(callBacks: callBacks))
    }

    public var body: some View {
        NavigationSplitView {
            list
                .appBackground()
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
                        .font(.app(.title2))
                    }
                    .appBackground()
                }
            }
        }
        .sheet(isPresented: $isCreating) {
            CreateExpenseView(callBacks: callBacks)
        }
        .onChange(of: opening, initial: true) { _, expenseId in
            if let expenseId {
                selection = expenseId
                opening = nil
            }
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
                .appPlainRows()
            }
        }
        .appListBackground()
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
                    .font(.app(.title2))
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
/// of it or owes, or that it was paid back. A settlement says who paid whom back instead, and a
/// deleted expense of a group is still listed, struck through and dimmed, as Android's `ExpenseCard`.
struct ExpenseRow: View {
    let expense: Expense
    let creditorName: String

    /// Who a settlement paid back, see `Expense.settlementRecipientUuid`: unused for any other
    /// expense.
    var recipientName = ""

    let selfUuid: String?

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                title
                    .font(.app(.headline))
                    .lineLimit(1)

                Group {
                    if expense.isSettlement {
                        Text("\(creditorName) paid \(recipientName)", bundle: .module)
                    } else {
                        Text("Paid by \(creditorName)", bundle: .module)
                    }
                }
                .font(.app(.subheadline))
                .foregroundStyle(AppColors.onSurfaceVariant)
                .lineLimit(1)
            }

            Spacer(minLength: 8)

            VStack(alignment: .trailing, spacing: 2) {
                Text(expense.amount.formattedAmount(in: expense.currency))
                    .font(.app(.headline))
                    .monospacedDigit()
                    .lineLimit(1)

                // Nobody owes anything of it any more.
                if expense.isSettled {
                    SettledLabel()
                        .font(.app(.subheadline))
                        .labelStyle(.titleAndIcon)
                        .lineLimit(1)
                } else if let balance = expense.shownBalance(selfUuid: selfUuid) {
                    Text(balance.formatted(in: expense.currency, withSign: true))
                        .font(.app(.subheadline))
                        .monospacedDigit()
                        .foregroundStyle(expense.role(selfUuid: selfUuid).color)
                        .lineLimit(1)
                }
            }
            .layoutPriority(1)
        }
        // Every text of a deleted one, which counts in no balance any more.
        .strikethrough(expense.isDeleted)
        .opacity(expense.isDeleted ? 0.6 : 1)
        // Which VoiceOver says, as it says neither of those.
        .accessibilityElement(children: .combine)
        .accessibilityValue(expense.isDeleted ? Text("Deleted", bundle: .module) : Text(verbatim: ""))
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private var title: some View {
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
}

#Preview {
    ExpenseListView(callBacks: PreviewExpenseCallBacks())
}
