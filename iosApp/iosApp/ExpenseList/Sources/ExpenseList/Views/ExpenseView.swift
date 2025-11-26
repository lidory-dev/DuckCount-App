import SwiftUI

public struct ExpenseView: View {
    let expense: Expense

    public var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Expense \(expense.id)")
                .font(.headline)

            Text("Description of the expense.")
                .font(.subheadline)
        }
        .padding()
    }
}

#Preview {
    ExpenseView(expense: Expense(id: "1"))
}

