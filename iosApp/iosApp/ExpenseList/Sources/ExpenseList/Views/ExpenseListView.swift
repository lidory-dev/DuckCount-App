import SwiftUI

public struct ExpenseListView: View {
    // TODO:
    struct Expense: Identifiable, Hashable {
        let id: String
    }
    
    // TODO:
    private var expenses: [ExpenseListView.Expense]
    
    @State private var expenseUuid: Optional<String>

    public init() {
        self.expenseUuid = Optional.none
        self.expenses = [
            Expense(id: "1"),
            Expense(id: "2"),
            Expense(id: "3"),
        ]
    }
    
    public var body: some View {
        NavigationSplitView {
            List(expenses, selection: $expenseUuid) { expense in
                Text(expense.id)
            }
        } detail: {
            if let uuid = expenseUuid {
                ExpenseDetailView(expenseUuid: uuid)
            } else {
                Text("Choose an expense to display more information about it.")
            }
        }
        
    }
}

#Preview {
    ExpenseListView()
}
