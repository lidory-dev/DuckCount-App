import SwiftUI

public struct ExpenseDetailView: View {
    let expense: Expense

    public init(expense: Expense) {
        self.expense = expense
    }

    public var body: some View {
        Text("Expense \(expense.id)")
    }
}

#Preview {
    ExpenseDetailView(expense: Expense(id: "1", currency: Currency(id: "1")))
}
