import SwiftUI

public struct ExpenseView: View {
    let expense: Expense

    public var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(expense.title)
                    .font(.headline)
                    .lineLimit(1)

                Spacer(minLength: 4)

                Text(expense.description)
                    .font(.subheadline)
                    .lineLimit(1)

                Spacer(minLength: 4)

                // TODO: Use the username of the creditor.
                Text("Paid by creditor")
                    .font(.caption)
                    .italic()
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if expense.status != .neutral {
                Text("\(expense.status.symbol) \(expense.currency.symbol)\(expense.amount)")
                    .font(.headline)
                    .foregroundStyle(expense.status.color)
                    .lineLimit(1)
            }
        }
        .padding()
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .shadow(color: .black.opacity(0.06), radius: 4, x: 0, y: 2)
    }
}

#Preview {
    VStack(spacing: 8) {
        ExpenseView(
            expense: Expense(
                id: "1",
                title: "Lunch",
                description: "Team lunch",
                amount: "25.00",
                currency: Currency(id: "1", name: "Dollar", symbol: "$")
            )
        )
        ExpenseView(
            expense: Expense(
                id: "2",
                title: "A very long expense title that should be truncated with ellipsis",
                description: "Description",
                amount: "1234.56",
                currency: Currency(id: "1", name: "Euro", symbol: "€")
            )
        )
    }
    .padding()
}
