import ExpenseList
import SwiftUI

struct ExpenseListScreen: View {
    @StateObject private var viewModel = ExpenseListViewModel()

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
            onCreateRandomExpenses: viewModel.createRandomExpenses
        )
    }
}
