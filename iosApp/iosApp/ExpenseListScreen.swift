import ExpenseList
import SwiftUI

struct ExpenseListScreen: View {
    @StateObject private var viewModel = ExpenseListViewModel()

    @State private var isCreatingExpense = false

    var body: some View {
        ExpenseListView(
            sections: viewModel.groupedExpenses.map { group in
                ExpenseSection(
                    id: group.id,
                    displayDate: group.displayDate,
                    expenses: group.expenses
                )
            },
            isLoading: viewModel.isLoading,
            onCreateRandomExpenses: viewModel.createRandomExpenses,
            onCreateExpense: { isCreatingExpense = true }
        )
        .sheet(isPresented: $isCreatingExpense) {
            CreateExpenseScreen()
        }
    }
}
