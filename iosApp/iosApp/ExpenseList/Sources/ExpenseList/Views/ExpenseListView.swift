import SwiftUI

public struct ExpenseListView: View {
    // TODO:
    private var expenses: [Expense]
    
    @State private var expense: Optional<Expense>

    public init() {
        self.expense = Optional.none
        self.expenses = [
            Expense(id: "1"),
            Expense(id: "2"),
            Expense(id: "3"),
        ]
    }
    
    /// Returns `true` if the device is not a phone (e.g., iPad or Mac).
    private var isBigScreen: Bool {
        return UIDevice.current.userInterfaceIdiom != .phone
    }
    
    private var bigScreenView: some View {
        NavigationSplitView {
            List(expenses, selection: $expense) { expense in
                Text(expense.id)
            }
        } detail: {
            if let expense = expense {
                ExpenseDetailView(expense: expense)
            } else {
                Text("Choose an expense to display more information about it.")
            }
        }
    }
    
    private var smallScreenView: some View {
        NavigationStack {
            List(expenses) { expense in
                NavigationLink {
                    ExpenseDetailView(expense: expense)
                } label: {
                    ExpenseView(expense: expense)
                }
            }
        }
    }
    
    public var body: some View {
        if isBigScreen {
            bigScreenView
        } else {
            smallScreenView
        }
    }
}

#Preview {
    ExpenseListView()
}
