import SwiftUI

public struct ExpenseListView: View {
    private let sections: [ExpenseSection]
    private let isLoading: Bool
    private let onCreateRandomExpenses: (() -> Void)?
    private let onCreateExpense: (() -> Void)?

    @State private var selectedExpense: Expense?
    @State private var navigationPath = NavigationPath()

    public init(
        sections: [ExpenseSection] = [],
        isLoading: Bool = false,
        onCreateRandomExpenses: (() -> Void)? = nil,
        onCreateExpense: (() -> Void)? = nil
    ) {
        self.sections = sections
        self.isLoading = isLoading
        self.onCreateRandomExpenses = onCreateRandomExpenses // TODO: Remove this.
        self.onCreateExpense = onCreateExpense
    }

    private var isBigScreen: Bool {
        UIDevice.current.userInterfaceIdiom != .phone
    }

    public var body: some View {
        if isBigScreen {
            NavigationSplitView {
                listContent
                    .navigationTitle("Expenses")
                    .toolbar {
                        createExpenseToolbarItem
                    }
            } detail: {
                if let expense = selectedExpense {
                    ExpenseDetailView(expense: expense)
                } else {
                    Text("Choose an expense to display more information about it.")
                        .foregroundStyle(.secondary)
                }
            }
        } else {
            NavigationStack(path: $navigationPath) {
                listContent
                .navigationTitle("Expenses")
                .navigationBarTitleDisplayMode(.large)
                .toolbar {
                    createExpenseToolbarItem
                }
                .navigationDestination(for: Expense.self) { expense in
                    ExpenseDetailView(expense: expense)
                }
            }
        }
    }

    @ToolbarContentBuilder
    private var createExpenseToolbarItem: some ToolbarContent {
        if let onCreateExpense {
            ToolbarItem(placement: .primaryAction) {
                Button(action: onCreateExpense) {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("New Expense")
            }
        }
    }

    @ViewBuilder
    private var listContent: some View {
        if isLoading {
            ProgressView()
        } else if sections.isEmpty {
            VStack(spacing: 16) {
                Text("No Expenses")
                    .font(.title2)
                    .foregroundStyle(.secondary)

                if let onCreateRandomExpenses {
                    Button("Create Random Expenses", action: onCreateRandomExpenses)
                        .buttonStyle(.borderedProminent)
                }
            }
        } else {
            List {
                ForEach(sections) { section in
                    Section {
                        ForEach(section.expenses) { expense in
                            ExpenseView(expense: expense)
                                .listRowBackground(Color.clear)
                                .listRowSeparator(.hidden)
                                .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                                .onTapGesture {
                                    if isBigScreen {
                                        selectedExpense = expense
                                    } else {
                                        navigationPath.append(expense)
                                    }
                                }
                        }
                    } header: {
                        SectionHeaderView(displayDate: section.displayDate)
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
    }
}

#Preview {
    ExpenseListView(
        sections: [
            ExpenseSection(
                id: "2026-04-08",
                displayDate: "Wednesday 08 April",
                expenses: [
                    Expense(id: "1", title: "Lunch", amount: "25.00", currency: Currency(id: "1", name: "Euro", symbol: "€"), status: .beneficiary),
                    Expense(id: "2", title: "A very long expense title that should be truncated with an ellipsis at the end", amount: "5.50", currency: Currency(id: "1", name: "Dollar", symbol: "$"), status: .creditor),
                ]
            ),
            ExpenseSection(
                id: "2024-01-14",
                displayDate: "Sunday 14 January 2024",
                expenses: [
                    Expense(id: "3", title: "Groceries", amount: "85.00", currency: Currency(id: "1", name: "Dollar", symbol: "$"), status: .neutral),
                ]
            ),
        ]
    )
}
